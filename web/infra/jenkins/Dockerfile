FROM docker:29.6.2-cli-alpine3.24 AS docker-cli

FROM jenkins/jenkins:2.555.3-lts-jdk21

USER root

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ca-certificates \
        git \
        openssl \
        python3 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins \
    /usr/local/libexec/docker/cli-plugins

COPY --chown=jenkins:jenkins infra/jenkins/init.groovy.d/01-executor-count.groovy \
    /usr/share/jenkins/ref/init.groovy.d/01-executor-count.groovy
COPY --chown=jenkins:jenkins infra/jenkins/init.groovy.d/02-temporary-space-monitor.groovy \
    /usr/share/jenkins/ref/init.groovy.d/02-temporary-space-monitor.groovy

USER jenkins
