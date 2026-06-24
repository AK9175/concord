# Build context is the repo root: docker build -f document-metadata-service/Dockerfile .
# The parent pom lists all 5 modules, and Maven validates every listed
# module directory exists before -pl even takes effect -- so the whole repo
# has to be copied in, even though this service has no com.collabdoc deps.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -pl document-metadata-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/document-metadata-service/target/document-metadata-service-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
