FROM docker:29.6.2-cli-alpine3.24 AS docker-cli
FROM ghcr.io/gitleaks/gitleaks:v8.29.0@sha256:71d3ee5990f2176f763b438298453fc37e87b119122045e176ca9d44ff00b08b AS gitleaks
FROM aquasec/trivy:0.70.0@sha256:be1190afcb28352bfddc4ddeb71470835d16462af68d310f9f4bca710961a41e AS trivy

FROM jenkins/jenkins:2.555.3-lts-jdk21

USER root

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ca-certificates \
        git \
        openssl \
        openssh-client \
        python3 \
        zstd \
    && rm -rf /var/lib/apt/lists/*

COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins \
    /usr/local/libexec/docker/cli-plugins
COPY --from=gitleaks /usr/bin/gitleaks /usr/local/bin/gitleaks
COPY --from=trivy /usr/local/bin/trivy /usr/local/bin/trivy

COPY --chown=jenkins:jenkins infra/jenkins/init.groovy.d/01-executor-count.groovy \
    /usr/share/jenkins/ref/init.groovy.d/01-executor-count.groovy
COPY --chown=jenkins:jenkins infra/jenkins/init.groovy.d/02-temporary-space-monitor.groovy \
    /usr/share/jenkins/ref/init.groovy.d/02-temporary-space-monitor.groovy

USER jenkins
