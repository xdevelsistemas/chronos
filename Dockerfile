FROM xdevelsistemas/debian-env:node-env

ADD . /chronos

WORKDIR /chronos

RUN mvn clean package

EXPOSE 8080

CMD ["bin/start-chronos.bash"]

