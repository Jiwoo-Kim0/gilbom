#!/bin/sh

set -eu

[ "$(id -u)" -eq 0 ] \
  || {
    echo "Run this script as root." >&2
    exit 1
  }

APP_OWNER="${APP_OWNER:-ubuntu}"
APP_GROUP="${APP_GROUP:-ubuntu}"
JENKINS_UID="${JENKINS_UID:-1000}"
JENKINS_GID="${JENKINS_GID:-1000}"
ARTIFACT_UPLOAD_GROUP="${ARTIFACT_UPLOAD_GROUP:-$APP_GROUP}"

getent passwd "$APP_OWNER" >/dev/null 2>&1 \
  || {
    echo "Application owner does not exist: $APP_OWNER" >&2
    exit 1
  }
getent group "$APP_GROUP" >/dev/null 2>&1 \
  || {
    echo "Application group does not exist: $APP_GROUP" >&2
    exit 1
  }
getent group "$ARTIFACT_UPLOAD_GROUP" >/dev/null 2>&1 \
  || {
    echo "Artifact upload group does not exist: $ARTIFACT_UPLOAD_GROUP" >&2
    exit 1
  }

install -d -o root -g root -m 0755 \
  /opt/gilbom \
  /opt/gilbom/deploy \
  /opt/gilbom/certbot
install -d -o "$APP_OWNER" -g "$APP_GROUP" -m 0775 \
  /opt/gilbom/app
install -d -o root -g "$ARTIFACT_UPLOAD_GROUP" -m 0710 \
  /opt/gilbom/artifacts
install -d -o root -g "$ARTIFACT_UPLOAD_GROUP" -m 0730 \
  /opt/gilbom/artifacts/.incoming
install -d -o root -g root -m 0750 \
  /opt/gilbom/monitoring
install -d -o root -g root -m 0700 \
  /opt/gilbom/secrets \
  /opt/gilbom/backups
install -d -o root -g root -m 0755 \
  /opt/jenkins
install -d -o "$JENKINS_UID" -g "$JENKINS_GID" -m 0700 \
  /opt/jenkins/home

echo "Day 8 host paths: PASS"
