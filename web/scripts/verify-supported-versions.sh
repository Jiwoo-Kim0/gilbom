#!/bin/sh

set -eu

assert_contains() {
  file=$1
  expected=$2
  label=$3

  if ! grep -Fq "$expected" "$file"; then
    echo "Version policy mismatch: ${label} (${file})" >&2
    exit 1
  fi
}

assert_count() {
  file=$1
  expected=$2
  expected_count=$3
  label=$4
  actual_count=$(grep -Fc "$expected" "$file" || true)

  if [ "$actual_count" -ne "$expected_count" ]; then
    echo "Version policy mismatch: ${label} (${file})" >&2
    exit 1
  fi
}

# 한 파일만 바뀌어 로컬·Docker·CI runtime이 갈라지는 상황을 빌드 전에 차단한다.
assert_contains backend/build.gradle \
  "id 'org.springframework.boot' version '3.5.16'" \
  "Spring Boot 3.5.16"
assert_contains backend/build.gradle \
  "JavaLanguageVersion.of(21)" \
  "Java toolchain 21"
assert_count backend/Dockerfile \
  "FROM amazoncorretto:21.0.12-alpine3.22 AS builder" \
  1 \
  "Java builder 21.0.12"
assert_contains backend/Dockerfile \
  "FROM alpine:3.22 AS runtime" \
  "Backend runtime Alpine 3.22"
assert_contains backend/Dockerfile \
  "COPY --from=builder /opt/java-runtime /opt/java/openjdk" \
  "jlink Corretto runtime"
assert_contains backend/gradle/wrapper/gradle-wrapper.properties \
  "gradle-8.14.3-bin.zip" \
  "Gradle Wrapper 8.14.3"
assert_contains .java-version "21.0.12" "local Java 21.0.12"

assert_contains frontend/Dockerfile \
  "node:24.18.0-alpine3.23" \
  "Node Docker 24.18.0"
assert_contains frontend/Dockerfile \
  "nginx:1.30.4-alpine AS runtime" \
  "Nginx runtime 1.30.4"
assert_contains frontend/Dockerfile \
  "USER nginx" \
  "Nginx non-root runtime"
assert_contains frontend/gilbom/.nvmrc "24.18.0" "local Node 24.18.0"
assert_contains frontend/gilbom/package.json \
  '"node": "24.18.0"' \
  "Node engine 24.18.0"
assert_contains frontend/gilbom/package.json \
  '"packageManager": "npm@11.16.0"' \
  "npm 11.16.0"
assert_contains frontend/gilbom/package.json \
  '"react": "19.2.8"' \
  "React 19.2.8"
assert_contains frontend/gilbom/package.json \
  '"react-dom": "19.2.8"' \
  "React DOM 19.2.8"
assert_contains frontend/gilbom/package-lock.json \
  '"react": "19.2.8"' \
  "locked React 19.2.8"
assert_contains frontend/gilbom/package-lock.json \
  '"react-dom": "19.2.8"' \
  "locked React DOM 19.2.8"

assert_count compose.prod.yaml \
  "image: eclipse-mosquitto:2.1.2-alpine" \
  2 \
  "production Mosquitto and config init 2.1.2"
assert_contains compose.prod.yaml \
  "image: postgis/postgis:16-3.5" \
  "production PostgreSQL/PostGIS 16-3.5"
assert_contains compose.prod.yaml \
  "image: certbot/certbot:v5.7.0" \
  "production Certbot 5.7.0"

echo "Runtime version pin policy: PASS"
