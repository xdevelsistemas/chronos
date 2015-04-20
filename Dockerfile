FROM xdevelsistemas/debian-env:node-env

ADD . /chronos

WORKDIR /chronos

RUN mvn clean package

RUN /etc/init.d/zookeeper start

EXPOSE 8081

VOLUME ["/etc/zookeeper", "/var/lib/zookeeper"]


CMD ["bin/start-chronos.bash","--http_port 8081"]

