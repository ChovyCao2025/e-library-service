FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21.0.13_9-jre
WORKDIR /app
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app app \
    && mkdir -p /app/logs/xxl-job \
    && chown -R app:app /app/logs
COPY --from=build /workspace/target/e-library-*.jar app.jar
USER app
EXPOSE 8080 9999
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
