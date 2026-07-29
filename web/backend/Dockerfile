# Java 21의 2026년 7월 CPU patch와 OS까지 고정하고 Gradle Wrapper만 사용한다.
FROM amazoncorretto:21.0.12-alpine3.22 AS builder

WORKDIR /workspace

# Windows checkout에서도 실행 비트가 달라지지 않도록 image 안의 Wrapper 권한을 명시한다.
COPY --chmod=755 gradlew ./gradlew
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon

COPY src ./src
# Docker build에는 host daemon socket을 주입하지 않는다. 전체 Testcontainers
# 검증은 scripts/backend-test.sh 및 Jenkins의 선행 gate에서 강제한다.
RUN ./gradlew clean test bootJar --no-daemon -PallowSkippedTests=true

FROM eclipse-mosquitto:2.1.2-alpine AS mqtt-tools

# 빌드와 운영의 Java vendor·patch가 달라지는 문제를 막기 위해 같은 이미지를 사용한다.
FROM amazoncorretto:21.0.12-alpine3.22 AS runtime

RUN apk add --no-cache curl cjson libcrypto3 \
    && addgroup -S -g 1883 mqttcred \
    && adduser -S -D -H -u 1883 -G mqttcred spring

WORKDIR /app

COPY --from=mqtt-tools /usr/bin/mosquitto_passwd /usr/local/bin/mosquitto_passwd
COPY --from=builder --chown=spring:mqttcred /workspace/build/libs/*.jar app.jar

USER spring

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
