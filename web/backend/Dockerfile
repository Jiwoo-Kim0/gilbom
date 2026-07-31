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

# bootJar와 모든 runtime dependency를 jdeps로 확인해 고정한 module 집합이다.
# TLS provider와 한국어 locale처럼 ServiceLoader/locale로 동적 접근하는 module은
# jdeps 결과에 나타나지 않으므로 명시적으로 포함한다.
RUN apk add --no-cache binutils \
    && jlink \
    --add-modules java.base,java.compiler,java.desktop,java.instrument,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.sql.rowset,jdk.crypto.ec,jdk.jfr,jdk.localedata,jdk.management,jdk.naming.dns,jdk.unsupported \
    --compress=zip-6 \
    --no-header-files \
    --no-man-pages \
    --strip-debug \
    --output /opt/java-runtime

FROM eclipse-mosquitto:2.1.2-alpine AS mqtt-tools

# 최종 단계는 JDK image가 아니라 Alpine + builder가 만든 Corretto runtime만 사용한다.
FROM alpine:3.22 AS runtime

ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    HOME=/tmp \
    LANG=C.UTF-8

COPY --from=builder /opt/java-runtime /opt/java/openjdk

# ca-certificates는 HTTPS/MQTT TLS에 필요하다. cjson과 libcrypto3는
# mosquitto_passwd의 실제 동적 링크 dependency다. Healthcheck는 Alpine
# BusyBox wget을 사용하므로 curl은 설치하지 않는다.
RUN apk add --no-cache ca-certificates cjson libcrypto3 \
    && addgroup -S -g 1883 mqttcred \
    && adduser -S -D -H -h /tmp -u 1883 -G mqttcred spring

WORKDIR /app

COPY --from=mqtt-tools /usr/bin/mosquitto_passwd /usr/local/bin/mosquitto_passwd
COPY --from=builder --chown=0:1883 --chmod=0440 /workspace/build/libs/*.jar app.jar
RUN mkdir -p \
        /app/data/report-pdf \
        /app/data/robot-images \
        /app/config/mqtt/credentials \
    && chown 1883:1883 \
        /app/data/report-pdf \
        /app/data/robot-images \
        /app/config/mqtt/credentials \
    && chmod 0770 \
        /app/data/report-pdf \
        /app/data/robot-images \
        /app/config/mqtt/credentials \
    && chmod 0555 /usr/local/bin/mosquitto_passwd

USER spring

EXPOSE 8080

STOPSIGNAL SIGTERM

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=10 \
    CMD wget --quiet --spider http://127.0.0.1:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-Djava.io.tmpdir=/tmp", "-Duser.home=/tmp", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
