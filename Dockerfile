FROM xdevelsistemas/debian-env:java7-env

RUN apt-get update && \ 
    apt-get install -y curl && \
    curl -sL https://deb.nodesource.com/setup | bash - && \
    apt-get install -y nodejs


RUN echo "deb http://repos.mesosphere.io/ubuntu/ trusty main" > /etc/apt/sources.list.d/mesosphere.list && \
    apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E56151BF && \
    apt-get update && \
    apt-get install -y maven \
    default-jdk \
    mesos \
    scala \
    curl && \
    apt-get clean all

ADD . /chronos

WORKDIR /chronos

RUN mvn clean package

EXPOSE 8080

ENTRYPOINT ["bin/start-chronos.bash"]
