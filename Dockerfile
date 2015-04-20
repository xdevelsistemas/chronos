FROM xdevelsistemas/debian-env:node-env

ADD . /chronos

WORKDIR /chronos

RUN mvn clean package

RUN /etc/init.d/zookeeper start

EXPOSE 2181 8080

VOLUME ["/etc/zookeeper", "/var/lib/zookeeper"]


CMD ["bin/start-chronos.bash"]

