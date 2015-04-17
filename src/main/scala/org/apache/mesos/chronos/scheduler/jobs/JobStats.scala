package org.apache.mesos.chronos.scheduler.jobs

import java.util.concurrent.ConcurrentHashMap
import java.util.logging.{Level, Logger}

import org.apache.mesos.chronos.scheduler.config.CassandraConfiguration
import com.datastax.driver.core._
import com.datastax.driver.core.exceptions.{DriverException, NoHostAvailableException, QueryExecutionException, QueryValidationException}
import com.datastax.driver.core.Row
import com.datastax.driver.core.querybuilder.{QueryBuilder, Insert}
import com.google.inject.Inject
import org.apache.mesos.Protos.{TaskState, TaskStatus}
import org.joda.time.DateTime

import scala.collection.JavaConverters._
import scala.collection.mutable.{HashMap, Map}

object CurrentState extends Enumeration {
  type CurrentState = Value
  val idle, queued, running = Value
}

class JobStats @Inject()(clusterBuilder: Option[Cluster.Builder], config: CassandraConfiguration) {

  // Cassandra table column names
  private val ATTEMPT: String = "attempt"
  private val ELEMENTS_PROCESSED = "elements_processed"
  private val IS_FAILURE: String = "is_failure"
  private val JOB_NAME: String = "job_name"
  private val JOB_OWNER: String = "job_owner"
  private val JOB_PARENTS: String = "job_parents"
  private val JOB_SCHEDULE: String = "job_schedule"
  private val MESSAGE: String = "message"
  private val SLAVE_ID: String = "slave_id"
  private val TASK_ID: String  = "id"
  private val TASK_STATE: String = "task_state"
  private val TIMESTAMP: String = "ts"

  protected val jobStates = new HashMap[String, CurrentState.Value]()

  val log = Logger.getLogger(getClass.getName)
  val statements = new ConcurrentHashMap[String, PreparedStatement]().asScala
  var _session: Option[Session] = None

  def getJobState(jobName: String) : CurrentState.Value = {
    /**
     * NOTE: currently everything stored in memory, look into moving
     * this to Cassandra. ZK is not an option cause serializers and
     * deserializers need to be written. Need a good solution, potentially
     * lots of writes and very few reads (only on failover)
     */
    val status = jobStates.get(jobName) match {
      case Some(s) =>
        s
      case _ =>
        CurrentState.idle
    }
    status
  }

  def updateJobState(jobName: String, state: CurrentState.Value) {
    var shouldUpdate = true
    jobStates.get(jobName) match {
      case Some(s: CurrentState.Value) => {
        if ((s == CurrentState.running) &&
            (state == CurrentState.queued)) {
          //don't update status if already running
          shouldUpdate = false
        }
      }
      case None =>
    }

    if (shouldUpdate) {
      log.info("Updating state for job (%s) to %s".format(jobName, state))
      jobStates.put(jobName, state)
    }
  }

  def removeJobState(job: BaseJob) {
    jobStates.remove(job.name)
  }

  /**
   * Queries Cassandra table for past and current job statistics by jobName
   * and limits by numTasks. The result is not sorted by execution time
   * @param job
   * @param numTasks
   * @return list of cassandra rows
   */
  private def getTaskDataByJob(job: BaseJob): Option[List[Row]] = {
    var rowsListFinal: Option[List[Row]] = None
    try {
      getSession match {
        case Some(session: Session) =>
          val query = s"SELECT * FROM ${config.cassandraTable()} WHERE ${JOB_NAME}='${job.name}';"
          val prepared = statements.getOrElseUpdate(query, {
            session.prepare(
              new SimpleStatement(query)
                .setConsistencyLevel(readConsistencyLevel())
                .asInstanceOf[RegularStatement]
            )
          })

          val resultSet = session.execute(prepared.bind())
          val rowsList = resultSet.all().asScala.toList
          rowsListFinal = Some(rowsList)
        case None => rowsListFinal = None
      }
    } catch {
      case e: NoHostAvailableException =>
        resetSession()
        log.log(Level.WARNING, "No hosts were available, will retry next time.", e)
      case e: QueryExecutionException =>
        log.log(Level.WARNING,"Query execution failed:", e)
      case e: QueryValidationException =>
        log.log(Level.WARNING,"Query validation failed:", e)
    }
    rowsListFinal
  }

  /**
   * Compare function for TaskStat by most recent date.
   */
  private def recentDateCompareFnc(a: TaskStat, b: TaskStat): Boolean = {
    var compareAscDate = a.taskStartTs match {
      case Some(aTs: DateTime) => {
        b.taskStartTs  match {
          case Some(bTs: DateTime) => {
            aTs.compareTo(bTs) <= 0
          }
          case None => {
            false
          }
        }
      }
      case None => {
        true
      }
    }
    !compareAscDate
  }

  /**
   * Queries Cassandra stat table to get the element processed count
   * for a specific job and a specific task
   * @param jobName
   * @param taskId
   * @return element processed count
   */
  private def getTaskStatCount(job: BaseJob, taskId: String): Option[Long] = {
    var taskStatCount: Option[Long] = None
    try {
      getSession match {
        case Some(session: Session) => {
          val query = s"SELECT * FROM ${config.cassandraStatCountTable()} WHERE job_name='${job.name}' AND task_id='${taskId}';"
          val prepared = statements.getOrElseUpdate(query, {
            session.prepare(
              new SimpleStatement(query)
                .setConsistencyLevel(readConsistencyLevel())
                .asInstanceOf[RegularStatement]
            )
          })
          val resultSet = session.execute(prepared.bind())

          //should just be one row
          val resultRow = resultSet.one()
          if (resultRow != null) {
            var cDef = resultRow.getColumnDefinitions()
            if (cDef.contains(ELEMENTS_PROCESSED)) {
              taskStatCount = Some(resultRow.getLong(ELEMENTS_PROCESSED))
            }
          } else {
            log.info("No elements processed count found for job_name %s taskId %s".format(job.name, taskId))
          }
        }
        case None =>
      }
    } catch {
      case e: NoHostAvailableException =>
        resetSession()
        log.log(Level.WARNING, "No hosts were available, will retry next time.", e)
      case e: QueryExecutionException =>
        log.log(Level.WARNING,"Query execution failed:", e)
      case e: QueryValidationException =>
        log.log(Level.WARNING,"Query validation failed:", e)
    }
    taskStatCount
  }

  /**
   * Determines if row from Cassandra represents a valid Mesos task
   * @param row
   * @return true if valid, false otherwise
   */
  private def isValidTaskData(row: Row): Boolean = {
    if (row == null) {
      false
    } else {
      val cDefs = row.getColumnDefinitions();
      cDefs.contains(JOB_NAME) &&
        cDefs.contains(TASK_ID) &&
        cDefs.contains(TIMESTAMP) &&
        cDefs.contains(TASK_STATE) &&
        cDefs.contains(SLAVE_ID) &&
        cDefs.contains(IS_FAILURE)
    }
  }

  /**
   * Parses the contents of the data row and updates the TaskStat object
   * @param taskStat
   * @param row
   * @return updated TaskStat object
   */
  private def updateTaskStat(taskStat: TaskStat, row: Row): TaskStat = {
    var taskTimestamp = row.getDate(TIMESTAMP)
    var taskState = row.getString(TASK_STATE)
    var slaveId = row.getString(SLAVE_ID)
    var isFailure = row.getBool(IS_FAILURE)

    if (TaskState.TASK_RUNNING.toString == taskState) {
      taskStat.setTaskStartTs(taskTimestamp)
      taskStat.setTaskStatus(ChronosTaskStatus.Running)
    } else if ((TaskState.TASK_FINISHED.toString() == taskState) ||
        (TaskState.TASK_FAILED.toString() == taskState) ||
        (TaskState.TASK_KILLED.toString() == taskState) ||
        (TaskState.TASK_LOST.toString() == taskState)) {

      //terminal state
      taskStat.setTaskEndTs(taskTimestamp)
      if (TaskState.TASK_FINISHED.toString() == taskState) {
        taskStat.setTaskStatus(ChronosTaskStatus.Success)
      } else {
        taskStat.setTaskStatus(ChronosTaskStatus.Fail)
      }
    }
    taskStat
  }

  /**
   * Returns a list of tasks (TaskStat) found for the specified job name
   * @param job
   * @return list of past and current running tasks for the job
   */
  private def getParsedTaskStatsByJob(job: BaseJob): List[TaskStat] = {
    val taskMap = Map[String, TaskStat]()

    val rowsListOpt = getTaskDataByJob(job) match {
      case Some(rowsList) => {
        for (row <- rowsList) {
          /*
           * Go through all the rows and construct a job history.
           * Group elements by task id
           */
          if (isValidTaskData(row)) {
            var taskId = row.getString(TASK_ID)
            var taskStat = taskMap.getOrElseUpdate(taskId,
                new TaskStat(taskId,
                    row.getString(JOB_NAME),
                    row.getString(SLAVE_ID)))
            updateTaskStat(taskStat, row)
          } else {
            log.info("Invalid row found in cassandra table for jobName=%s".format(job.name))
          }
        }
      }
      case None => {
        log.info("No row list found for jobName=%s".format(job.name))
      }
    }

    taskMap.values.toList
  }

  /**
   * Returns most recent tasks by job and returns only numTasks
   * @param job
   * @param numTasks
   * @return returns a list of past and currently running tasks,
   *         the first element is the most recent.
   */
  def getMostRecentTaskStatsByJob(job: BaseJob, numTasks: Int): List[TaskStat] = {
    val taskStatList = getParsedTaskStatsByJob(job)

    /*
     * Data is not sorted yet, so sort jobs by most recent date
     */
    var sortedDescTaskStatList = taskStatList.sortWith(recentDateCompareFnc)

    /*
     * limit output here
     */
    sortedDescTaskStatList = sortedDescTaskStatList.slice(0, numTasks)

    if (job.dataProcessingJobType) {
      /*
       * Retrieve stat count for these tasks. This should be done
       * after slicing as an optimization.
       */
      for (taskStat <- sortedDescTaskStatList) {
        var elementCount = getTaskStatCount(job, taskStat.taskId)
        taskStat.numElementsProcessed = elementCount
      }
    }

    sortedDescTaskStatList
  }

  /**
   * Updates the number of elements processed by a task. This method
   * is not idempotent
   * @param job
   * @param taskId
   * @param addtionalElementsProcessed
   */
  def updateTaskProgress(job: BaseJob,
      taskId: String,
      additionalElementsProcessed: Long) {
    try {
      getSession match {
        case Some(session: Session) =>
          val validateQuery = s"SELECT * FROM ${config.cassandraTable()} WHERE job_name='${job.name}' AND id='${taskId}';"
          var prepared = statements.getOrElseUpdate(validateQuery, {
            session.prepare(
              new SimpleStatement(validateQuery)
                .setConsistencyLevel(readConsistencyLevel())
                .asInstanceOf[RegularStatement]
            )
          })
          val validateResultSet = session.execute(prepared.bind())

          if (validateResultSet.one() != null) {
            /*
             * Only update stat count if entry exists in main table.
             */
            val query = s"UPDATE ${config.cassandraStatCountTable()}"+
              s" SET elements_processed = elements_processed + ${additionalElementsProcessed}"+
              s" WHERE job_name='${job.name}' AND task_id='${taskId}';"
            prepared = statements.getOrElseUpdate(query, {
              session.prepare(
                new SimpleStatement(query)
                  .asInstanceOf[RegularStatement]
              )
            })
          } else {
            throw new IllegalArgumentException("Task id  %s not found".format(taskId))
          }
          val resultSet = session.executeAsync(prepared.bind())
        case None =>
      }
    } catch {
      case e: NoHostAvailableException =>
        resetSession()
        log.log(Level.WARNING, "No hosts were available, will retry next time.", e)
      case e: QueryExecutionException =>
        log.log(Level.WARNING,"Query execution failed:", e)
      case e: QueryValidationException =>
        log.log(Level.WARNING,"Query validation failed:", e)
    }
  }

  def jobQueued(job: BaseJob, attempt: Int) {
    updateJobState(job.name, CurrentState.queued)
  }

  def jobStarted(job: BaseJob, taskStatus: TaskStatus, attempt: Int) {
    updateJobState(job.name, CurrentState.running)

    var jobSchedule:Option[String] = None
    var jobParents:Option[java.util.Set[String]] = None
    job match {
      case job: ScheduleBasedJob =>
        jobSchedule = Some(job.schedule)
      case job: DependencyBasedJob =>
        jobParents = Some(job.parents.asJava)
    }
    insertToStatTable(
            id=Some(taskStatus.getTaskId.getValue),
            timestamp=Some(new java.util.Date()),
            jobName=Some(job.name),
            jobOwner=Some(job.owner),
            jobSchedule=jobSchedule,
            jobParents=jobParents,
            taskState=Some(taskStatus.getState.toString),
            slaveId=Some(taskStatus.getSlaveId.getValue),
            message=None,
            attempt=Some(attempt),
            isFailure=None)
  }

  def getSession: Option[Session] = {
    _session match {
      case Some(s) => Some(s)
      case None =>
        clusterBuilder match {
          case Some(c) =>
            try {
              val session = c.build.connect()
              session.execute(new SimpleStatement(
                s"USE ${config.cassandraKeyspace()};"
              ))

              session.execute(new SimpleStatement(
                s"CREATE TABLE IF NOT EXISTS ${config.cassandraTable()}" +
                  """
                    |(
                    |   id             VARCHAR,
                    |   ts             TIMESTAMP,
                    |   job_name       VARCHAR,
                    |   job_owner      VARCHAR,
                    |   job_schedule   VARCHAR,
                    |   job_parents    SET<VARCHAR>,
                    |   task_state     VARCHAR,
                    |   slave_id       VARCHAR,
                    |   message        VARCHAR,
                    |   attempt        INT,
                    |   is_failure     BOOLEAN,
                    | PRIMARY KEY (id, ts))
                    | WITH bloom_filter_fp_chance=0.100000 AND
                    | compaction = {'class':'LeveledCompactionStrategy'}
                  """.stripMargin
              ))
              session.execute(new SimpleStatement(
                s"CREATE INDEX IF NOT EXISTS ON ${config.cassandraTable()} (${JOB_NAME});"
              ))

              /*
               * highest bloom filter to reduce memory consumption and reducing
               * false positives
               */
              session.execute(new SimpleStatement(
                s"CREATE TABLE IF NOT EXISTS ${config.cassandraStatCountTable()}" +
                  """
                    |(
                    |   task_id              VARCHAR,
                    |   job_name             VARCHAR,
                    |   elements_processed   COUNTER,
                    | PRIMARY KEY (job_name, task_id))
                    | WITH bloom_filter_fp_chance=0.100000 AND
                    | compaction = {'class':'LeveledCompactionStrategy'}
                  """.stripMargin
              ))

              _session = Some(session)
              _session
            } catch {
              case e: DriverException =>
                log.log(Level.WARNING, "Caught exception when creating Cassandra JobStats session", e)
                None
            }
          case None => None
        }
    }
  }

  def resetSession() {
    statements.clear()
    _session match {
      case Some(session) =>
        session.close()
      case _ =>
    }
    _session = None
  }

  def jobFinished(job: BaseJob, taskStatus: TaskStatus, attempt: Int) {
    updateJobState(job.name, CurrentState.idle)

    var jobSchedule:Option[String] = None
    var jobParents:Option[java.util.Set[String]] = None
    job match {
      case job: ScheduleBasedJob =>
        jobSchedule = Some(job.schedule)
      case job: DependencyBasedJob =>
        jobParents = Some(job.parents.asJava)
    }
    insertToStatTable(
            id=Some(taskStatus.getTaskId.getValue),
            timestamp=Some(new java.util.Date()),
            jobName=Some(job.name),
            jobOwner=Some(job.owner),
            jobSchedule=jobSchedule,
            jobParents=jobParents,
            taskState=Some(taskStatus.getState.toString),
            slaveId=Some(taskStatus.getSlaveId.getValue),
            message=None,
            attempt=Some(attempt),
            isFailure=None)
  }

  def jobFailed(job: BaseJob, taskStatus: TaskStatus, attempt: Int) {
    updateJobState(job.name, CurrentState.idle)

    var jobSchedule:Option[String] = None
    var jobParents:Option[java.util.Set[String]] = None
    job match {
      case job: ScheduleBasedJob =>
        jobSchedule = Some(job.schedule)
      case job: DependencyBasedJob =>
        jobParents = Some(job.parents.asJava)
    }
    insertToStatTable(
            id=Some(taskStatus.getTaskId.getValue),
            timestamp=Some(new java.util.Date()),
            jobName=Some(job.name),
            jobOwner=Some(job.owner),
            jobSchedule=jobSchedule,
            jobParents=jobParents,
            taskState=Some(taskStatus.getState.toString),
            slaveId=Some(taskStatus.getSlaveId.getValue),
            message=Some(taskStatus.getMessage),
            attempt=Some(attempt),
            isFailure=Some(true))
  }

  /**
   * Overloaded method of jobFailed. Reports that a job identified by only
   * its job name failed during execution. This is only used to report
   * a failure when there is no corresponding job object, which only happens
   * when a job is destroyed. When a job is destroyed, all tasks are killed
   * and this method is called when a task is killed.
   * @param jobName
   * @param taskStatus
   * @param attempt
   */
  def jobFailed(jobName: String, taskStatus: TaskStatus, attempt: Int) {
    updateJobState(jobName, CurrentState.idle)

    insertToStatTable(
        id=Some(taskStatus.getTaskId.getValue),
        timestamp=Some(new java.util.Date()),
        jobName=Some(jobName),
        jobOwner=None, jobSchedule=None, jobParents=None,
        taskState=Some(taskStatus.getState().toString()),
        slaveId=Some(taskStatus.getSlaveId().getValue()),
        message=Some(taskStatus.getMessage()),
        attempt=Some(attempt),
        isFailure=Some(true))
  }

  /**
   * Helper method that performs an insert statement to update the
   * job statistics (chronos) table. All arguments are surrounded
   * by options so that a subset of values can be inserted.
   */
  private def insertToStatTable(id: Option[String],
      timestamp: Option[java.util.Date],
      jobName: Option[String],
      jobOwner: Option[String],
      jobSchedule: Option[String],
      jobParents: Option[java.util.Set[String]],
      taskState: Option[String],
      slaveId: Option[String],
      message: Option[String],
      attempt: Option[Integer],
      isFailure: Option[Boolean]) = {
    try {
      getSession match {
        case Some(session: Session) =>
          var query:Insert = QueryBuilder.insertInto(config.cassandraTable())

          //set required values (let these throw an exception)
          query.value(TASK_ID , id.get)
            .value(JOB_NAME , jobName.get)
            .value(TIMESTAMP , timestamp.get)

          jobOwner match {
            case Some(jo: String) => query.value(JOB_OWNER , jo)
            case _ =>
          }
          jobSchedule match {
            case Some(js: String) => query.value(JOB_SCHEDULE , js)
            case _ =>
          }
          jobParents match {
            case Some(jp: java.util.Set[String]) => query.value(JOB_PARENTS , jp)
            case _ =>
          }
          taskState match {
            case Some(ts: String) => query.value(TASK_STATE , ts)
            case _ =>
          }
          slaveId match {
            case Some(s: String) => query.value(SLAVE_ID , s)
            case _ =>
          }
          message match {
            case Some(m: String) => query.value(MESSAGE , m)
            case _ =>
          }
          attempt match {
            case Some(a: Integer) => query.value(ATTEMPT , a)
            case _ =>
          }
          isFailure match {
            case Some(f: Boolean) => query.value(IS_FAILURE , f)
            case _ =>
          }

          query.setConsistencyLevel(ConsistencyLevel.valueOf(config.cassandraConsistency()))
            .asInstanceOf[RegularStatement]

          session.executeAsync(query)
        case None =>
      }
    } catch {
      case e: NoHostAvailableException =>
        resetSession()
        log.log(Level.WARNING, "No hosts were available, will retry next time.", e)
      case e: QueryExecutionException =>
        log.log(Level.WARNING,"Query execution failed: ", e)
      case e: QueryValidationException =>
        log.log(Level.WARNING,"Query validation failed: ", e)
    }

  }

  private def readConsistencyLevel() : ConsistencyLevel = {
    if (ConsistencyLevel.ANY.name().equalsIgnoreCase(config.cassandraConsistency())) {
      //reads do not support ANY
      ConsistencyLevel.ONE
    } else {
      ConsistencyLevel.valueOf(config.cassandraConsistency())
    }
  }
}
