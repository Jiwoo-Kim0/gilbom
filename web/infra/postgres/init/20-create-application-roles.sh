#!/bin/sh
set -eu

# Docker entrypoint가 빈 DB를 초기화할 때만 실행된다.
# 비밀번호를 SQL 문자열에 직접 연결하지 않고 psql 변수와 format()으로 안전하게 전달한다.
: "${POSTGRES_DB:?POSTGRES_DB must be set}"
: "${POSTGRES_FLYWAY_USER:?POSTGRES_FLYWAY_USER must be set}"
: "${POSTGRES_FLYWAY_PASSWORD:?POSTGRES_FLYWAY_PASSWORD must be set}"
: "${POSTGRES_APP_USER:?POSTGRES_APP_USER must be set}"
: "${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD must be set}"

case "$POSTGRES_FLYWAY_USER:$POSTGRES_APP_USER" in
  *[!a-z0-9_:]*)
    echo "Database role names must contain only lowercase letters, digits, and underscores." >&2
    exit 1
    ;;
esac

if [ "$POSTGRES_USER" = "$POSTGRES_FLYWAY_USER" ] \
  || [ "$POSTGRES_USER" = "$POSTGRES_APP_USER" ] \
  || [ "$POSTGRES_FLYWAY_USER" = "$POSTGRES_APP_USER" ]; then
  echo "Bootstrap, Flyway, and runtime database roles must be different." >&2
  exit 1
fi

psql \
  --set ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set database_name="$POSTGRES_DB" \
  --set flyway_user="$POSTGRES_FLYWAY_USER" \
  --set flyway_password="$POSTGRES_FLYWAY_PASSWORD" \
  --set app_user="$POSTGRES_APP_USER" \
  --set app_password="$POSTGRES_APP_PASSWORD" <<'SQL'
-- Flyway role은 schema와 table을 소유하지만 운영 데이터 접근에는 사용하지 않는다.
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE',
    :'flyway_user',
    :'flyway_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'flyway_user')
\gexec

-- Runtime role은 로그인과 migration이 부여한 DML만 수행한다.
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE',
    :'app_user',
    :'app_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user')
\gexec

REVOKE CREATE ON DATABASE :"database_name" FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

GRANT CONNECT, CREATE ON DATABASE :"database_name" TO :"flyway_user";
GRANT CONNECT ON DATABASE :"database_name" TO :"app_user";

-- Flyway history는 runtime 계정이 읽을 필요가 없는 별도 schema에 둔다.
CREATE SCHEMA IF NOT EXISTS flyway_history AUTHORIZATION :"flyway_user";
REVOKE ALL ON SCHEMA flyway_history FROM PUBLIC;

ALTER ROLE :"flyway_user" SET timezone TO 'UTC';
ALTER ROLE :"app_user" SET timezone TO 'UTC';
SQL
