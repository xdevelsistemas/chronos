package org.apache.mesos.chronos.scheduler.jobs

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{TimeUnit, Executors, Future}
import java.util.logging.{Level, Logger}

import akka.actor.ActorSystem
import org.apache.mesos.chronos.scheduler.graph.JobGraph
import org.apache.mesos.chronos.scheduler.mesos.MesosDriverFactory
import org.apache.mesos.chronos.scheduler.state.PersistenceStore
import com.google.common.util.concurrent.AbstractIdleService
import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.leader.{LeaderLatch, LeaderLatchListener}
import org.apache.mesos.Protos.TaskStatus
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone, Duration, Period}

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

/**
 * Constructs concrete tasks given a  list of schedules and a global scheduleHorizon.
 * The schedule horizon represents the advance-time the schedule is constructed.
 *
 * A lot of the methods in this class are broken into small pieces to allow for better unit testing.
 * @author Florian Leibert (flo@leibert.de)
 */
class JobScheduler @Inject()(val scheduleHorizon: Period,
                             val taskManager: TaskManager,
                             val jobGraph: JobGraph,
                             val persistenceStore: PersistenceStore,
                             val mesosDriver: MesosDriverFactory = null,
                             val curator: CuratorFramework = null,
                             val leaderLatch: LeaderLatch = null,
                             val leaderPath: String = null,
                             val jobsObserver: JobsObserver.Observer,
                             val failureRetryDelay: Long = 60000,
                             val disableAfterFailures: Long = 0,
                             val jobMetrics: JobMetrics)
//Allows us to let Chaos manage the lifecycle of this class.
  extends AbstractIdleService {

  val localExecutor = Executors.newFixedThreadPool(1)
  val schedulerThreadFuture = new AtomicReference[Future[_]]
  val leaderExecutor = Executors.newSingleThreadExecutor()
  //This acts as the lock
  val lock = new Object

  val actorSystem = ActorSystem()
  val akkaScheduler = actorSystem.scheduler

  //TODO(FL): Take some methods out of this class.
  val running = new AtomicBoolean(false)
  val leader = new AtomicBoolean(false)
  private[this] val log = Logger.getLogger(getClass.getName)
  var streams: List[ScheduleStream] = List()

  def isLeader: Boolean = leader.get()

  def getLeader: String = {
    try {
      leaderLatch.getLeader.getId
    } catch {
      case e: Exception =>
        log.log(Level.SEVERE, "Error trying to talk to zookeeper. Exiting.", e)
        System.exit(1)
        null
    }
  }

  def isTaskAsync(taskId: String): Boolean = {
    val TaskUtils.taskIdPattern(_, _, jobName, _) = taskId
    jobGraph.lookupVertex(jobName) match {
      case Some(baseJob: BaseJob) => baseJob.async
      case _ => false
    }
  }

  /**
   * Update job definition
   * @param oldJob job definition
   * @param newJob new job definition
   */
  def updateJob(oldJob: BaseJob, newJob: BaseJob) {
    //TODO(FL): Ensure we're using job-ids rather than relying on jobs names for identification.
    assert(newJob.name == oldJob.name, "Renaming jobs is currently not supported!")

    newJob match {
      case scheduleBasedJob: ScheduleBasedJob =>
        lock.synchronized {
          if (!scheduleBasedJob.disabled) {
            val newStreams = List(JobUtils.makeScheduleStream(scheduleBasedJob, DateTime.now(DateTimeZone.UTC)))
              .filter(_.nonEmpty).map(_.get)
            if (newStreams.nonEmpty) {
              log.info("updating ScheduleBasedJob:" + scheduleBasedJob.toString)
              val tmpStreams = streams.filter(_.head._2 != scheduleBasedJob.name)
              streams = iteration(DateTime.now(DateTimeZone.UTC), newStreams ++ tmpStreams)
            }
          } else {
            log.info("updating ScheduleBasedJob:" + scheduleBasedJob.toString)
            val tmpStreams = streams.filter(_.head._2 != scheduleBasedJob.name)
            streams = iteration(DateTime.now(DateTimeZone.UTC), tmpStreams)
          }
        }
      case _ =>
    }
    replaceJob(oldJob, newJob)
  }

  def reset(purgeQueue: Boolean = false) {
    lock.synchronized {
      streams = List()
      jobGraph.reset()
      if (purgeQueue) {
        log.warning("Purging locally queued tasks!")
        taskManager.flush()
      }
    }
  }

  def registerJob(job: BaseJob, persist: Boolean, dateTime: DateTime) {
    registerJob(List(job), persist, dateTime)
  }

  /**
   * This method should be used to register jobs.
   */
  def registerJob(jobs: List[BaseJob], persist: Boolean = false, dateTime: DateTime = DateTime.now(DateTimeZone.UTC)) {
    lock.synchronized {
      require(isLeader, "Cannot register a job with this scheduler, not the leader!")
      val scheduleBasedJobs = ListBuffer[ScheduleBasedJob]()
      val dependencyBasedJobs = ListBuffer[DependencyBasedJob]()

      jobs.foreach {
        case x: DependencyBasedJob =>
          dependencyBasedJobs += x
        case x: ScheduleBasedJob =>
          scheduleBasedJobs += x
        case x: Any =>
          throw new IllegalStateException("Error, job is neither ScheduleBased nor DependencyBased:" + x.toString)

      }

      if (scheduleBasedJobs.nonEmpty) {
        val newStreams = scheduleBasedJobs.filter(!_.disabled).map(JobUtils.makeScheduleStream(_, dateTime)).filter(_.nonEmpty).map(_.get)
        scheduleBasedJobs.foreach({
          job =>
            jobGraph.addVertex(job)
            if (persist) {
              log.info("Persisting job:" + job.name)
              persistenceStore.persistJob(job)
            }
        })
        if (newStreams.nonEmpty) {
          addSchedule(dateTime, newStreams.toList)
        }
      }

      if (dependencyBasedJobs.nonEmpty) {
        dependencyBasedJobs.foreach({
          job =>
            val parents = jobGraph.parentJobs(job)
            log.info("Job parent: [ %s ], name: %s, command: %s".format(job.parents.mkString(","), job.name, job.command))
            jobGraph.addVertex(job)
            parents.foreach(x => jobGraph.addDependency(x.name, job.name))
            if (persist) {
              log.info("Persisting job:" + job.name)
              persistenceStore.persistJob(job)
            }
        })
      }
    }
  }

  def deregisterJob(job: BaseJob, persist: Boolean = false) {
    require(isLeader, "Cannot deregister a job with this scheduler, not the leader!")
    lock.synchronized {
      log.info("Removing vertex")

      jobGraph.getChildren(job.name)
        .map(x => jobGraph.lookupVertex(x).get)
        .filter {
        case j: DependencyBasedJob => true
        case _ => false
      }
        .map(x => x.asInstanceOf[DependencyBasedJob])
        .filter(x => x.parents.size > 1)
        .foreach({
        childJob =>
          log.info("Updating job %s".format(job.name))
          val copy = childJob.copy(parents = childJob.parents.filter(_ != job.name))
          updateJob(childJob, copy)
      })

      jobGraph.removeVertex(job)
      job match {
        case scheduledJob: ScheduleBasedJob =>
          removeSchedule(scheduledJob)
          log.info("Removed schedule based job")
          log.info("Size of streams:" + streams.size)
        case dependencyBasedJob: DependencyBasedJob =>
          //TODO(FL): Check if there are empty edges.
          log.info("Job removed from dependency graph.")
        case _: Any =>
          throw new IllegalArgumentException("Cannot handle the job type")
      }

      taskManager.cancelTasks(job)
      taskManager.removeTasks(job)
      jobsObserver.apply(JobRemoved(job))

      if (persist) {
        log.info("Removing job from underlying state abstraction:" + job.name)
        persistenceStore.removeJob(job)
      }
    }
  }

  def handleStartedTask(taskStatus: TaskStatus) {
    val taskId = taskStatus.getTaskId.getValue
    if (!TaskUtils.isValidVersion(taskId)) {
      log.warning("Found old or invalid task, ignoring!")
      return
    }
    val jobName = TaskUtils.getJobNameForTaskId(taskId)
    val jobOption = jobGraph.lookupVertex(jobName)

    if (jobOption.isEmpty) {
      log.warning("Job '%s' no longer registered.".format(jobName))
    } else {
      val job = jobOption.get
      val (_, _, attempt, _) = TaskUtils.parseTaskId(taskId)
      jobsObserver.apply(JobStarted(job, taskStatus, attempt))

      job match {
        case j: DependencyBasedJob =>
          jobGraph.resetDependencyInvocations(j.name)
        case _ =>
      }
    }
  }

  /**
   * Takes care of follow-up actions for a finished task, i.e. update the job schedule in the persistence store or
   * launch tasks for dependent jobs
   */
  def handleFinishedTask(taskStatus: TaskStatus, taskDate: Option[DateTime] = None) {
    // `taskDate` is purely for unit testing
    val taskId = taskStatus.getTaskId.getValue
    if (!TaskUtils.isValidVersion(taskId)) {
      log.warning("Found old or invalid task, ignoring!")
      return
    }
    val jobName = TaskUtils.getJobNameForTaskId(taskId)
    val jobOption = jobGraph.lookupVertex(jobName)

    if (jobOption.isEmpty) {
      log.warning("Job '%s' no longer registered.".format(jobName))
    } else {
      val (_, start, attempt, _) = TaskUtils.parseTaskId(taskId)
      jobMetrics.updateJobStat(jobName, timeMs = DateTime.now(DateTimeZone.UTC).getMillis - start)
      jobMetrics.updateJobStatus(jobName, success = true)
      val job = jobOption.get
      jobsObserver.apply(JobFinished(job, taskStatus, attempt))

      val newJob = job match {
        case job: ScheduleBasedJob =>
          job.copy(successCount = job.successCount + 1,
            errorsSinceLastSuccess = 0,
            lastSuccess = DateTime.now(DateTimeZone.UTC).toString)
        case job: DependencyBasedJob =>
          job.copy(successCount = job.successCount + 1,
            errorsSinceLastSuccess = 0,
            lastSuccess = DateTime.now(DateTimeZone.UTC).toString)
        case _ =>
          throw new IllegalArgumentException("Cannot handle unknown task type")
      }
      replaceJob(job, newJob)
      processDependencies(jobName, taskDate)

      log.fine("Cleaning up finished task '%s'".format(taskId))

      /* TODO(FL): Fix.
         Cleanup potentially exhausted job. Note, if X tasks were fired within a short period of time (~ execution time
        of the job, the first returning Finished-task may trigger deletion of the job! This is a known limitation and
        needs some work but should only affect long running frequent finite jobs or short finite jobs with a tiny pause
        in between */
      job match {
        case job: ScheduleBasedJob =>
          val scheduleBasedJob: ScheduleBasedJob = newJob.asInstanceOf[ScheduleBasedJob]
          Iso8601Expressions.parse(scheduleBasedJob.schedule, scheduleBasedJob.scheduleTimeZone) match {
            case Some((recurrences, _, _)) =>
              if (recurrences == 0) {
                log.info("Disabling job that reached a zero-recurrence count!")

                val disabledJob: ScheduleBasedJob = scheduleBasedJob.copy(disabled = true)
                jobsObserver.apply(JobDisabled(job, """Job '%s' has exhausted all of its recurrences and has been disabled.
                                                        |Please consider either removing your job, or updating its schedule and re-enabling it.
                                                      """.stripMargin.format(job.name)))
                replaceJob(scheduleBasedJob, disabledJob)
              }
            case None =>
          }
        case _ =>
      }
    }
  }

  def replaceJob(oldJob: BaseJob, newJob: BaseJob) {
    lock.synchronized {
      jobGraph.replaceVertex(oldJob, newJob)
      persistenceStore.persistJob(newJob)
    }
  }

  private def processDependencies(jobName: String, taskDate: Option[DateTime]) {
    val dependents = jobGraph.getExecutableChildren(jobName)
    if (dependents.nonEmpty) {
      log.fine("%s has dependents: %s .".format(jobName, dependents.mkString(",")))
      dependents.foreach {
        //TODO(FL): Ensure that the job for the given x exists. Lock.
        x =>
          val dependentJob = jobGraph.getJobForName(x).get
          if (!dependentJob.disabled) {
            val date = taskDate match {
              case Some(d) => d
              case None => DateTime.now(DateTimeZone.UTC)
            }
            taskManager.enqueue(TaskUtils.getTaskId(dependentJob,
              date), dependentJob.highPriority)

            log.fine("Enqueued depedent job." + x)
          }
      }
    } else {
      log.fine("%s does not have any ready dependents.".format(jobName))
    }
  }

  def handleFailedTask(taskStatus: TaskStatus) {
    val taskId = taskStatus.getTaskId.getValue
    if (!TaskUtils.isValidVersion(taskId)) {
      log.warning("Found old or invalid task, ignoring!")
    } else {
      val (jobName, _, attempt, _) = TaskUtils.parseTaskId(taskId)
      log.warning("Task of job: %s failed.".format(jobName))
      val jobOption = jobGraph.lookupVertex(jobName)
      jobOption match {
        case Some(job) =>
          jobsObserver.apply(JobFailed(Right(job), taskStatus, attempt))

          val hasAttemptsLeft: Boolean = attempt < job.retries
          val hadRecentSuccess: Boolean = try {
            job.lastError.length > 0 && job.lastSuccess.length > 0 &&
              (DateTime.parse(job.lastSuccess).getMillis - DateTime.parse(job.lastError).getMillis) >= 0
          } catch {
            case ex: IllegalArgumentException =>
              log.warning(s"Couldn't parse last run date from ${job.name}")
              false
            case _: Exception => false
          }

          if (hasAttemptsLeft && (job.lastError.length == 0 || hadRecentSuccess)) {
            log.warning("Retrying job: %s, attempt: %d".format(jobName, attempt))
            /* Schedule the retry up to 60 seconds in the future */
            val delayDuration = new Duration(failureRetryDelay)
            val newTaskId = TaskUtils.getTaskId(job, DateTime.now(DateTimeZone.UTC)
              .plus(delayDuration), attempt + 1)
            val delayedTask = new Runnable {
              def run() {
                log.info(s"Enqueuing failed task $newTaskId")
                taskManager.persistTask(newTaskId, job)
                taskManager.enqueue(newTaskId, job.highPriority)
              }
            }
            implicit val executor = actorSystem.dispatcher

            akkaScheduler.scheduleOnce(
              delay = scala.concurrent.duration.Duration(delayDuration.getMillis, TimeUnit.MILLISECONDS),
              runnable = delayedTask)
          } else {
            val disableJob =
              (disableAfterFailures > 0) && (job.errorsSinceLastSuccess + 1 >= disableAfterFailures)

            val lastErrorTime = DateTime.now(DateTimeZone.UTC)
            val newJob = {
              job match {
                case job: ScheduleBasedJob =>
                  job.copy(errorCount = job.errorCount + 1,
                    errorsSinceLastSuccess = job.errorsSinceLastSuccess + 1,
                    lastError = lastErrorTime.toString, disabled = disableJob)
                case job: DependencyBasedJob =>
                  job.copy(errorCount = job.errorCount + 1,
                    errorsSinceLastSuccess = job.errorsSinceLastSuccess + 1,
                    lastError = lastErrorTime.toString, disabled = disableJob)
                case _ => throw new IllegalArgumentException("Cannot handle unknown task type")
              }
            }
            updateJob(job, newJob)
            if (job.softError) processDependencies(jobName, Option(lastErrorTime))

            // Handle failure by either disabling the job and notifying the owner,
            // or just notifying the owner.
            if (disableJob) {
              log.warning("Job failed beyond retries! Job will now be disabled after "
                + newJob.errorsSinceLastSuccess + " failures (disableAfterFailures=" + disableAfterFailures + ").")
              val msg = "\nFailed at '%s', %d failures since last success\nTask id: %s\n"
                .format(DateTime.now(DateTimeZone.UTC), newJob.errorsSinceLastSuccess, taskId)
              jobsObserver.apply(JobDisabled(job, TaskUtils.appendSchedulerMessage(msg, taskStatus)))
            } else {
              log.warning("Job failed beyond retries!")
              jobsObserver.apply(JobRetriesExhausted(job, taskStatus, attempt))
            }
            jobMetrics.updateJobStatus(jobName, success = false)
          }
        case None =>
          log.warning("Could not find job for task: %s Job may have been deleted while task was in flight!"
            .format(taskId))
      }
    }
  }

  /**
   * Task has been killed. Do appropriate cleanup
   * Possible reasons for task being killed:
   *   -invoked kill via task manager API
   *   -job is deleted
   */
  def handleKilledTask(taskStatus: TaskStatus) {
    val taskId = taskStatus.getTaskId.getValue
    if (!TaskUtils.isValidVersion(taskId)) {
      log.warning("Found old or invalid task, ignoring!")
      return
    }

    val (jobName, start, attempt, _) = TaskUtils.parseTaskId(taskId)
    val jobOption = jobGraph.lookupVertex(jobName)

    jobsObserver.apply(JobFailed(jobOption.toRight(jobName), taskStatus, attempt))
  }

  /**
   * Iterates through the stream for the given DateTime and a list of schedules, removing old schedules and acting on
   * the available schedules.
   * @param dateTime for which to process schedules
   * @param schedules schedules to be processed
   * @return list of updated schedules
   */
  def iteration(dateTime: DateTime, schedules: List[ScheduleStream]): List[ScheduleStream] = {
    log.info("Checking schedules with time horizon:%s".format(scheduleHorizon.toString))
    removeOldSchedules(schedules.map(s => scheduleStream(dateTime, s)))
  }

  def run(dateSupplier: () => DateTime) {
    log.info("Starting run loop for JobScheduler. CurrentTime: %s".format(DateTime.now(DateTimeZone.UTC)))
    while (running.get) {
      lock.synchronized {
        log.info("Size of streams: %d".format(streams.size))
        streams = iteration(dateSupplier(), streams)
      }
      Thread.sleep(scheduleHorizon.toStandardDuration.getMillis)
      //TODO(FL): This can be inaccurate if the horizon >= 1D on daylight savings day and when leap seconds are introduced.
    }
    log.info("No longer running.")
  }

  /**
   * Given a stream and a DateTime(@see org.joda.DateTime), this method returns a 2-tuple with a ScheduleTask and
   * a clipped schedule stream in case that the ScheduleTask was not none. Returns no task and the input stream,
   * if nothing needs scheduling within the time horizon.
   * @param now time to start iteration with
   * @param stream schedule stream
   * @return
   */
  @tailrec
  final def next(now: DateTime, stream: ScheduleStream): (Option[ScheduledTask], Option[ScheduleStream]) = {
    val (schedule, jobName, scheduleTimeZone) = stream.head

    log.info("Calling next for stream: %s, jobname: %s".format(stream.schedule, jobName))
    assert(schedule != null && !schedule.equals(""), "No valid schedule found: " + schedule)
    assert(jobName != null, "BaseJob cannot be null")

    var jobOption: Option[BaseJob] = None
    //TODO(FL): wrap with lock.
    try {
      jobOption = jobGraph.lookupVertex(jobName)
      if (jobOption.isEmpty) {
        log.warning("-----------------------------------")
        log.warning("Warning, no job found in graph for:" + jobName)
        log.warning("-----------------------------------")
        //This might happen during loading stage in case of failover.
        return (None, None)
      }
    } catch {
      case ex: IllegalArgumentException =>
        log.warning(s"Corrupt job in stream for $jobName")
    }

    Iso8601Expressions.parse(schedule, scheduleTimeZone) match {
      case Some((recurrences, nextDate, _)) =>
        log.finest("Recurrences: '%d', next date: '%s'".format(recurrences, stream.schedule))
        //nextDate has to be > (now - epsilon) & < (now + timehorizon) , for it to be scheduled!
        if (recurrences == 0) {
          log.info("Finished all recurrences of job '%s'".format(jobName))
          //We're not removing the job here because it may still be required if a pending task fails.
          (None, None)
        } else {
          val job = jobOption.get
          val scheduleWindowBegin = now.minus(job.epsilon)
          val scheduleWindowEnd = now.plus(scheduleHorizon)
          if (nextDate.isAfter(scheduleWindowBegin) && nextDate.isBefore(scheduleWindowEnd)) {
            log.info("Task ready for scheduling: %s".format(nextDate))
            //TODO(FL): Rethink passing the dispatch queue all the way down to the ScheduledTask.
            val task = new ScheduledTask(TaskUtils.getTaskId(job, nextDate), nextDate, job, taskManager)
            return (Some(task), stream.tail)
          }
          // Next instance is too far in the future
          // Needs to be scheduled at a later time, after schedule horizon.
          if (!nextDate.isBefore(now)) {
            return (None, Some(stream))
          }
          // Next instance is too far in the past (beyond epsilon)
          //TODO(FL): Think about the semantics here and see if it always makes sense to skip ahead of missed schedules.
          log.fine("No need to work on schedule: '%s' yet".format(nextDate))
          jobsObserver.apply(JobSkipped(job, nextDate))
          val tail = stream.tail
          if (tail.isEmpty) {
            //TODO(FL): Verify that this can go.
            persistenceStore.removeJob(job)
            log.warning("\n\nWARNING\n\nReached the tail of the streams which should have been never reached \n\n")
            (None, None)
          } else {
            log.info("tail: " + tail.get.schedule + " now: " + now)
            next(now, tail.get)
          }
        }
      case None =>
        log.warning(s"Couldn't parse date for $jobName")
        (None, Some(stream))
    }
  }

  def removeSchedule(deletedStream: BaseJob) {
    lock.synchronized {
      log.fine("Removing schedules: ")
      streams = streams.filter(_.jobName != deletedStream.name)
      log.fine("Size of streams: %d".format(streams.size))
    }
  }

  //Begin Service interface
  override def startUp() {
    assert(!running.get, "This scheduler is already running!")
    log.info("Trying to become leader.")

    leaderLatch.addListener(new LeaderLatchListener {
      override def notLeader(): Unit = {
        leader.set(false)
        onDefeated()
      }

      override def isLeader(): Unit = {
        leader.set(true)
        onElected()
      }
    }, leaderExecutor)
    leaderLatch.start()
  }

  override def shutDown() {
    running.set(false)
    log.info("Shutting down job scheduler")

    leaderLatch.close(LeaderLatch.CloseMode.NOTIFY_LEADER)
    leaderExecutor.shutdown()
  }

  //Begin Leader interface, which is required for CandidateImpl.
  def onDefeated() {
    mesosDriver.close()

    log.info("Defeated. Not the current leader.")
    running.set(false)
    jobGraph.reset() // So we can rebuild it later.
    schedulerThreadFuture.get.cancel(true)
  }

  def onElected() {
    log.info("Elected as leader.")

    running.set(true)
    lock.synchronized {
      try {
        //It's important to load the tasks first, otherwise a job that's due will trigger a task right away.
        log.info("Loading tasks")
        TaskUtils.loadTasks(taskManager, persistenceStore)
        log.info("Loading jobs")
        JobUtils.loadJobs(this, persistenceStore)
      } catch {
        case e: Exception =>
          log.log(Level.SEVERE, "Loading tasks or jobs failed. Exiting.", e)
          System.exit(1)
      }
    }

    val jobScheduler = this
    //Consider making this a background thread or control via an executor.

    val f = localExecutor.submit(
      new Thread() {
        override def run() {
          log.info("Running background thread")
          val dateSupplier = () => {
            DateTime.now(DateTimeZone.UTC)
          }
          jobScheduler.run(dateSupplier)
        }
      })

    schedulerThreadFuture.set(f)
    log.info("Starting chronos driver")
    mesosDriver.start()
  }

  // Generates a new ScheduleStream based on a DateTime and a ScheduleStream. Side effects of this method
  // are that a new Job may be persisted in the underlying persistence store and a task might get dispatched.
  @tailrec
  private final def scheduleStream(now: DateTime, s: ScheduleStream): Option[ScheduleStream] = {
    val (taskOption, stream) = next(now, s)
    if (taskOption.isEmpty) {
      stream
    } else {
      val encapsulatedJob = taskOption.get.job
      log.info("Scheduling:" + taskOption.get.job.name)
      taskManager.scheduleDelayedTask(taskOption.get, taskManager.getMillisUntilExecution(taskOption.get.due), persist = true)
      /*TODO(FL): This needs some refactoring. Ideally, the task should only be persisted once it has been submitted
                  to chronos, however if we were to do this with the current design, there could be missed tasks if
                  the scheduler went down before having fired off the jobs, since we're scheduling ahead of time.

                  Instead we persist the tasks right away, which also has the disadvantage of us maybe executing a job
                  twice IFF the scheduler goes down after the jobs have been submitted to chronos and stored in the queue
                  and us still being unavailable before the failover timeout. Thus we set the failover timeout to one
                  week. This means we should receive a chronos message of a successful task as long as we're not down for
                  more than a week for the above mentioned scenario.

                  E.g. Schedule 5seconds into the future
                  j1 -> R10/20:00:00/PT1S
                  19:00:56: queue(j1t1, j1t2, j1t3, j1t4, j1t5)
                  19:00:56: persist(R5/20:00:05/PT1S)
                  19:00:56: persist(j1t1, j1t2, j1t3, j1t4, j1t5)
                  19:00:57: DOWN
                  19:00:58: UP
                  ...
       */


      /* TODO(FL): The invocation count only represents the number of job invocations, not the number of successful
         executions. When a scheduler starts up, it needs to verify that there are no pending tasks.
         This isn't really transactional but should be sufficiently reliable for most usecases. To outline why it is not
         really transactional. To fix this, we need to add a new state into ZK that stores the successful tasks.
       */
      encapsulatedJob match {
        case job: ScheduleBasedJob =>
          val updatedJob = job.copy(stream.get.schedule)
          log.info("Saving updated job:" + updatedJob)
          persistenceStore.persistJob(updatedJob)
          jobGraph.replaceVertex(encapsulatedJob, updatedJob)
        case _ =>
          log.warning(s"Job ${encapsulatedJob.name} is not a scheduled job!")
      }

      if (stream.isEmpty) {
        return stream
      }
      scheduleStream(now, stream.get)
    }
  }

  //End Service interface

  private def removeOldSchedules(scheduleStreams: List[Option[ScheduleStream]]): List[ScheduleStream] = {
    log.fine("Filtering out empty streams")
    scheduleStreams.filter(s => s.isDefined && s.get.tail.isDefined).map(_.get)
  }

  /**
   * Adds a List of ScheduleStream and runs a iteration at the current time.
   * @param now time from which to evaluate schedule
   * @param newStreams new schedules to be evaluated
   */
  private def addSchedule(now: DateTime, newStreams: List[ScheduleStream]) {
    log.info("Adding schedule for time:" + now.toString(DateTimeFormat.fullTime()))
    lock.synchronized {
      log.fine("Starting iteration")
      streams = iteration(now, newStreams ++ streams)
      log.fine("Size of streams: %d".format(streams.size))
    }
  }

  //End Leader interface
}
