# Build context is the repo root: docker build -f document-service/Dockerfile .
# The parent pom lists every module, and Maven validates each one's directory
# exists before -pl even takes effect -- so the whole repo has to be copied
# in, not just document-service's own sibling dependencies (ot-core,
# document-service-proto).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -pl document-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/document-service/target/document-service-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
