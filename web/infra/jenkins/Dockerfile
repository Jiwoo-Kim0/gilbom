FROM docker:29.6.2-cli-alpine3.24 AS docker-cli
FROM ghcr.io/gitleaks/gitleaks:v8.29.0@sha256:71d3ee5990f2176f763b438298453fc37e87b119122045e176ca9d44ff00b08b AS gitleaks
FROM aquasec/trivy:0.70.0@sha256:be1190afcb28352bfddc4ddeb71470835d16462af68d310f9f4bca710961a41e AS trivy

FROM jenkins/jenkins:2.555.3-lts-jdk21

USER root

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ca-certificates \
        coreutils \
        curl \
        git \
        gzip \
        jq \
        openssl \
        openssh-client \
        python3 \
        zstd \
    && rm -rf /var/lib/apt/lists/*

COPY infra/cosign/version.env /tmp/cosign-version.env
RUN set -eu; \
    . /tmp/cosign-version.env; \
    case "$(dpkg --print-architecture)" in \
      amd64) \
        asset=cosign-linux-amd64; \
        expected_sha256="$COSIGN_LINUX_AMD64_SHA256" \
        ;; \
      arm64) \
        asset=cosign-linux-arm64; \
        expected_sha256="$COSIGN_LINUX_ARM64_SHA256" \
        ;; \
      *) echo "Unsupported Jenkins architecture" >&2; exit 1 ;; \
    esac; \
    curl \
      --proto '=https' \
      --tlsv1.2 \
      --fail \
      --silent \
      --show-error \
      --location \
      --output /tmp/cosign \
      "https://github.com/sigstore/cosign/releases/download/v$COSIGN_VERSION/$asset"; \
    printf '%s  %s\n' "$expected_sha256" /tmp/cosign \
      | sha256sum -c -; \
    install -o root -g root -m 0755 /tmp/cosign /usr/local/bin/cosign; \
    cosign version | grep -Fq "GitVersion:    v$COSIGN_VERSION"; \
    rm -f /tmp/cosign /tmp/cosign-version.env

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
