FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw
COPY src ./src
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/target/KnowFlowAI-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p /data/documents /data/lucene
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
