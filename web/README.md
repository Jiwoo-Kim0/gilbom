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
- [OpenAPI v1](./docs/openapi-v1.yaml)

## Local infrastructure

The Docker Compose setup runs PostgreSQL 16 with PostGIS, the Spring Boot
backend, and the React frontend served by Nginx for local development.

### Start the application

1. Copy `.env.example` to `.env`.
2. Replace the three PostgreSQL password placeholders in `.env` with distinct
   local development passwords.
3. Build and start the services.

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
