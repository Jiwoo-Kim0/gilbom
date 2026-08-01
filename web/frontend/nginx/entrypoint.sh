#!/bin/sh

set -eu

/usr/local/bin/gilbom-nginx-configure configure
exec nginx -g "daemon off;"
