# Build context is the repo root: docker build -f frontend-server/Dockerfile .
# The parent pom lists all 5 modules, and Maven validates every listed
# module directory exists before -pl even takes effect -- so the whole repo
# has to be copied in, even though this service has no com.collabdoc deps.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -pl frontend-server -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/frontend-server/target/frontend-server-0.1.0-SNAPSHOT.jar app.jar
# FrontendServer reads static files from a filesystem path (-Dfrontend.dir),
# not the classpath -- the static directory has to be copied alongside the
# jar and pointed at explicitly, unlike connection-tier/document-metadata-
# service, which need nothing beyond their jar.
COPY --from=build /app/frontend-server/src/main/resources/static static
EXPOSE 8082
ENTRYPOINT ["java", "-Dfrontend.dir=static", "-jar", "app.jar"]
