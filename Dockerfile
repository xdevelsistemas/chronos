FROM xdevelsistemas/debian-env:java7-env

RUN apt-get update && \ 
    apt-get install -y curl && \
    curl -sL https://deb.nodesource.com/setup | bash - && \
    apt-get install -y nodejs


RUN echo "deb http://repos.mesosphere.io/ubuntu/ trusty main" > /etc/apt/sources.list.d/mesosphere.list && \
    apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF && \
    apt-get update && \
    apt-get install -y maven \
    default-jdk \
    mesos \
    scala \
    curl

ADD . /chronos

WORKDIR /chronos

RUN mvn clean package

EXPOSE 8081

ENTRYPOINT ["bin/start-chronos.bash"]
