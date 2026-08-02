FROM nginx:1.30.4-alpine

ARG DRILL_RELEASE_SHA

RUN test -n "$DRILL_RELEASE_SHA"

LABEL org.gilbom.rollback-drill.release-sha="$DRILL_RELEASE_SHA"

COPY --chmod=0444 nginx.conf /etc/nginx/nginx.conf

HEALTHCHECK --interval=2s --timeout=2s --retries=5 --start-period=1s \
    CMD wget --quiet --output-document=- http://127.0.0.1:8080/healthz \
        | grep -Fxq ok

USER 101:101

ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
