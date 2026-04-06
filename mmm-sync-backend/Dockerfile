FROM gradle:8.10-jdk21 AS build
WORKDIR /app

COPY build.gradle settings.gradle ./
COPY src ./src

RUN gradle --no-daemon clean build

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/build/libs/mmm-sync-backend-1.0.1.jar /app/mmm-sync-backend.jar

ENV PORT=8787
EXPOSE 8787

CMD ["java", "-jar", "/app/mmm-sync-backend.jar"]
