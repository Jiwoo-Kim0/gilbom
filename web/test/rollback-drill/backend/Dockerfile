FROM postgres:16.10-alpine3.22

ARG DRILL_RELEASE_SHA
ARG DRILL_TARGET_FLYWAY_VERSION
ARG DRILL_MAX_SUPPORTED_FLYWAY
ARG DRILL_FORCE_UNHEALTHY

RUN test -n "$DRILL_RELEASE_SHA" \
    && test -n "$DRILL_TARGET_FLYWAY_VERSION" \
    && test -n "$DRILL_MAX_SUPPORTED_FLYWAY" \
    && case "$DRILL_FORCE_UNHEALTHY" in true|false) ;; *) exit 1 ;; esac \
    && mkdir -p /opt/gilbom-drill \
    && chmod 0755 /opt/gilbom-drill \
    && printf '%s\n' \
        "DRILL_RELEASE_SHA=$DRILL_RELEASE_SHA" \
        "DRILL_TARGET_FLYWAY_VERSION=$DRILL_TARGET_FLYWAY_VERSION" \
        "DRILL_MAX_SUPPORTED_FLYWAY=$DRILL_MAX_SUPPORTED_FLYWAY" \
        "DRILL_FORCE_UNHEALTHY=$DRILL_FORCE_UNHEALTHY" \
        > /opt/gilbom-drill/release.env \
    && chmod 0444 /opt/gilbom-drill/release.env

COPY --chmod=0555 entrypoint.sh /usr/local/bin/gilbom-drill-backend
COPY --chmod=0555 healthcheck.sh /usr/local/bin/gilbom-drill-backend-healthcheck
COPY --chmod=0555 compatibility-probe.sh /usr/local/bin/gilbom-drill-compatibility-probe

EXPOSE 8080

HEALTHCHECK --interval=2s --timeout=2s --retries=5 --start-period=2s \
    CMD ["/usr/local/bin/gilbom-drill-backend-healthcheck"]

ENTRYPOINT ["/usr/local/bin/gilbom-drill-backend"]
