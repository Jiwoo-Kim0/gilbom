#!/bin/sh

set -eu

if grep -Eq 'listen \[?::\]?:?8443 ssl|listen 8443 ssl' \
    /tmp/nginx-conf.d/site.conf 2>/dev/null; then
  exec wget \
    --quiet \
    --spider \
    --no-check-certificate \
    https://127.0.0.1:8443/healthz
fi

exec wget --quiet --spider http://127.0.0.1:8080/healthz
