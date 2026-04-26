# --- build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
# Prime the dependency cache separately from source for faster rebuilds
RUN mvn -B -q -e -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# --- runtime stage ---
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
EXPOSE 8088
ENTRYPOINT ["java","-jar","/app/app.jar"]
