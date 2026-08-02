#!/bin/sh

set -eu

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
temporary="$(mktemp -d)"
trap 'rm -rf -- "$temporary"' EXIT HUP INT TERM

mkdir -p "$temporary/secrets" "$temporary/output"
printf 'ordinary build output\n' > "$temporary/build.log"
fixture_secret="$(printf 'fixture-%s-%s' 'secret-material' '123456789')"
printf '%s\n' "$fixture_secret" > "$temporary/secrets/test-secret"

# 로컬 contract test에는 gitleaks binary가 없을 수 있으므로 성공 동작만 하는
# fixture를 PATH 앞에 둔다. 실제 Jenkins image는 고정 Gitleaks binary를 사용한다.
mkdir -p "$temporary/bin"
cat > "$temporary/bin/gitleaks" <<'EOF'
#!/bin/sh
set -eu
report=""
while [ "$#" -gt 0 ]
do
  case "$1" in
    --report-path)
      shift
      report="$1"
      ;;
  esac
  shift
done
[ -n "$report" ]
printf '[]\n' > "$report"
EOF
chmod 0755 "$temporary/bin/gitleaks"

PATH="$temporary/bin:$PATH" \
JENKINS_LOG_PATH="$temporary/build.log" \
SECRET_SCAN_DIR="$temporary/secrets" \
JENKINS_LOG_SECURITY_OUTPUT_DIR="$temporary/output" \
  sh "$PROJECT_DIR/scripts/verify-jenkins-console-secret.sh"
grep -Fxq PASS "$temporary/output/status"

printf '%s\n' "$fixture_secret" >> "$temporary/build.log"
if PATH="$temporary/bin:$PATH" \
    JENKINS_LOG_PATH="$temporary/build.log" \
    SECRET_SCAN_DIR="$temporary/secrets" \
    JENKINS_LOG_SECURITY_OUTPUT_DIR="$temporary/output" \
      sh "$PROJECT_DIR/scripts/verify-jenkins-console-secret.sh" \
      >/dev/null 2>&1
then
  echo "Jenkins console Secret scan self-test failed: exact Secret was accepted" >&2
  exit 1
fi

echo "Jenkins console Secret scan self-test: PASS"
