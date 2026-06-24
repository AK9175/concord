# Build context is the repo root: docker build -f connection-tier/Dockerfile .
# The parent pom lists all 5 modules, and Maven validates every listed
# module directory exists before -pl even takes effect -- so the whole repo
# has to be copied in, not just connection-tier's own sibling dependencies.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -pl connection-tier -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/connection-tier/target/connection-tier-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
