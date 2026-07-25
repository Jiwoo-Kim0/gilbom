FROM gradle:8.14.3-jdk21-alpine AS builder

WORKDIR /workspace

COPY --chown=gradle:gradle build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon

COPY --chown=gradle:gradle src ./src
RUN gradle clean test bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN apk add --no-cache curl \
    && addgroup -S spring \
    && adduser -S spring -G spring

WORKDIR /app

COPY --from=builder --chown=spring:spring /workspace/build/libs/*.jar app.jar

USER spring

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
