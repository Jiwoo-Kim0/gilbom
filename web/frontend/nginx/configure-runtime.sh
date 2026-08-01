#!/bin/sh

set -eu

mode=${GILBOM_NGINX_TLS_MODE:-off}
action=${1:-configure}
runtime_dir=/tmp/nginx-conf.d
runtime_site="$runtime_dir/site.conf"
certificate=/etc/nginx/certs/fullchain.pem
private_key=/etc/nginx/certs/privkey.pem

case "$mode" in
  off)
    source_site=/etc/nginx/gilbom/site-http.conf
    ;;
  auto)
    if [ -s "$certificate" ] && [ -r "$private_key" ]; then
      source_site=/etc/nginx/gilbom/site-https.conf
    else
      source_site=/etc/nginx/gilbom/site-bootstrap.conf
    fi
    ;;
  on)
    [ -s "$certificate" ] \
      || {
        echo "TLS certificate is missing: $certificate" >&2
        exit 1
      }
    [ -r "$private_key" ] \
      || {
        echo "TLS private key is not readable: $private_key" >&2
        exit 1
      }
    source_site=/etc/nginx/gilbom/site-https.conf
    ;;
  *)
    echo "GILBOM_NGINX_TLS_MODE must be off, auto, or on" >&2
    exit 1
    ;;
esac

mkdir -p "$runtime_dir"
temporary_site="$runtime_site.$$"
cp "$source_site" "$temporary_site"
chmod 0444 "$temporary_site"
mv "$temporary_site" "$runtime_site"

nginx -t

case "$action" in
  configure)
    ;;
  reload)
    nginx -s reload
    ;;
  *)
    echo "usage: gilbom-nginx-configure [configure|reload]" >&2
    exit 1
    ;;
esac
