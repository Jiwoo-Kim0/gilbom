# Gilbom Web

## API and Day 1 contracts

- [Day 1 contract index](./docs/day1-contracts.md)
- [API contract](./docs/api-contract.md)
- [Administrator authentication and security](./docs/admin-authentication-security.md)
- [Authorization matrix](./docs/authorization-matrix.md)
- [Audit and sensitive data policy](./docs/audit-and-sensitive-data-policy.md)
- [Threat model](./docs/threat-model.md)
- [Environment configuration strategy](./docs/environment-configuration-strategy.md)
- [Report status contract](./docs/report-status-contract.md)
- [Common API responses and errors](./docs/common-api-response-and-errors.md)
- [Database migrations](./docs/database-migrations.md)
- [TOTP Secret encryption](./docs/totp-secret-encryption.md)
- [Jenkins pipeline](./docs/jenkins-pipeline.md)
- [Runtime version baseline](./docs/runtime-versions.md)
- [HTTP request limits and security headers](./docs/http-security-baseline.md)
- [Day 7 Secret injection](./docs/day7-secret-injection.md)
- [Day 7 Backend runtime image](./docs/day7-backend-runtime-image.md)
- [Day 7 Frontend runtime image](./docs/day7-frontend-runtime-image.md)
- [Day 7 production Compose and networks](./docs/day7-production-compose.md)
- [Day 7 health, readiness, and shutdown](./docs/day7-health-readiness-shutdown.md)
- [Day 7 production image verification](./docs/day7-production-image-verification.md)
- [Day 7 container report smoke](./docs/day7-container-report-smoke.md)
- [Day 7 resilience and persistence verification](./docs/day7-resilience-verification.md)
- [Production deployment Runbook](./docs/production-deployment-runbook.md)
- [Stage 10 production deploy account](./docs/production-deploy-account.md)
- [Stage 11 root production deploy entrypoint](./docs/production-root-deploy.md)
- [Day 7 completion and scoped feature freeze](./docs/day7-completion-freeze.md)
- [OpenAPI v1](./docs/openapi-v1.yaml)

## Local infrastructure

The Docker Compose setup runs PostgreSQL 16 with PostGIS, the Spring Boot
backend, and the React frontend served by Nginx for local development.

### Start the application

1. Copy `.env.example` to `.env`.
2. Create the ignored `secrets/` files named in `.env`; use distinct local
   development values, directory mode `0700`, and file mode `0444`. Do not put
   their contents in `.env`.
3. Set the public `VITE_NAVER_MAP_CLIENT_ID` in `.env`.
4. Build and start the services.

```bash
docker compose up --build -d
```

Check its status:

```bash
docker compose ps
docker compose logs -f backend
```

Check the backend:

```bash
curl http://localhost:8080/api/health
curl http://localhost:8080/actuator/health
```

Check the frontend Nginx readiness:

```bash
curl http://localhost:3000/healthz
```

Open the frontend at `http://localhost:3000`. Requests under `/api` are
forwarded by Nginx to the backend service.

Stop the services while preserving database data:

```bash
docker compose down
```

Delete the local database volume only when a full reset is intended:

```bash
docker compose down --volumes
```

The database port is bound to `127.0.0.1` so it is not exposed on other host
interfaces. PostgreSQL data is persisted in the `postgres_data` named volume.
Flyway migrations run with a schema-owner account that is separate from the
backend runtime account. Spring Session JDBC tables are also managed by Flyway.

## Compose services

- `db`: PostgreSQL 16 + PostGIS
- `backend`: Spring Boot API
- `frontend`: React static build served by Nginx

Production-specific settings belong in `compose.prod.yaml`; real credentials
must be supplied by the deployment environment rather than committed files.

## Production Compose

Copy `.env.prod.example` to the ignored `.env.prod`, provision every referenced
Secret file, then validate and start the production stack:

```bash
ENV_FILE=.env.prod sh scripts/verify-production-compose.sh
VITE_NAVER_MAP_CLIENT_ID='<public-client-id>' \
  docker compose --env-file .env.prod -f compose.build.yaml build backend frontend
docker compose --env-file .env.prod -f compose.prod.yaml up --no-build -d --wait
IMAGE_SCANNER_MODE=required sh scripts/verify-production-images.sh
sh scripts/verify-frontend-tls.sh
sh scripts/container-resilience-smoke.sh
```

Production Compose never builds application images. Frontend Nginx publishes
HTTP 80 only for ACME HTTP-01 and HTTPS redirect, and serves the application
exclusively on HTTPS 443. Robot-facing MQTT publishes TLS 8883. Backend and
PostgreSQL remain reachable only through their internal Compose networks.
Before the first certificate exists, Frontend exposes only the ACME webroot and
bootstrap health response; `infra/certbot/certificate.sh issue` installs the
certificate copy and reloads Nginx into HTTPS mode.

`container-resilience-smoke.sh`는 보고서 전체 smoke를 먼저 실행한 뒤 같은 격리
stack에서 Backend/Frontend 자동 재시작, DB·MQTT·LLM 장애 복구, Secret 경계와
DB·PDF·이미지 영속성까지 검증한다. 모든 일회성 container·volume·Secret은 자동
정리된다. 실제 GMS canary는 기본적으로 실행하지 않는다.
