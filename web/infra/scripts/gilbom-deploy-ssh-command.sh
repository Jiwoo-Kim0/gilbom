#!/bin/sh

set -eu
set +x

fail() {
  echo "Production deploy SSH command rejected: $1" >&2
  exit 1
}

[ "${USER:-}" = deploy ] || [ "$(id -un)" = deploy ] \
  || fail "only the deploy account may use this command"

command_line="${SSH_ORIGINAL_COMMAND:-}"
prefix='sudo --non-interactive /usr/local/sbin/gilbom-deploy '
case "$command_line" in
  "$prefix"*) release_sha="${command_line#"$prefix"}" ;;
  *) fail "only the fixed production deploy command is allowed" ;;
esac

printf '%s\n' "$release_sha" | grep -Eq '^[0-9a-f]{40}$' \
  || fail "release SHA must contain 40 lowercase hexadecimal characters"
[ "$command_line" = "$prefix$release_sha" ] \
  || fail "additional arguments are forbidden"

exec sudo --non-interactive \
  /usr/local/sbin/gilbom-deploy "$release_sha"
