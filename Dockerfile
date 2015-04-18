FROM xdevelsistemas/debian-env:node-env

ADD . /chronos

WORKDIR /chronos

RUN mvn clean package

EXPOSE 8081

CMD ["bin/start-chronos.bash"]

