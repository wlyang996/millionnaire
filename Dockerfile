# 微信云托管：目标目录留空（仓库根目录），Dockerfile 名称 Dockerfile，端口 80
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY server ./server
COPY gateway ./gateway
RUN cd server && mvn -B -q -Dmaven.test.skip=true install
RUN cd gateway && mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/gateway/target/app.jar app.jar
EXPOSE 80
ENTRYPOINT ["java", "-jar", "app.jar"]
