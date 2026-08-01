#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
SOURCE_DIR="$PROJECT_DIR/frontend/gilbom/src"

fail() {
  echo "Frontend CSP source verification failed: $1" >&2
  exit 1
}

[ -d "$SOURCE_DIR" ] || fail "Frontend source directory is missing"

# `style-src 'self'`에서는 JSX style prop, SDK marker HTML의 style attribute,
# CSSOM style mutation이 모두 차단된다. 정적 CSS class 또는 semantic element를
# 사용하도록 source 단계에서 회귀를 막는다.
if grep -RInE \
    --include='*.ts' \
    --include='*.tsx' \
    --include='*.js' \
    --include='*.jsx' \
    'style[[:space:]]*=' \
    "$SOURCE_DIR"; then
  fail "inline style attribute or JSX style prop was found"
fi

if grep -RInE \
    --include='*.ts' \
    --include='*.tsx' \
    --include='*.js' \
    --include='*.jsx' \
    '\.style\.|cssText|setAttribute[[:space:]]*\([[:space:]]*['\''"]style['\''"]' \
    "$SOURCE_DIR"; then
  fail "runtime DOM style mutation was found"
fi

echo "Frontend CSP source: PASS"
