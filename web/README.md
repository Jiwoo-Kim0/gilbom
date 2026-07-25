# Gilbom Web

## Local infrastructure

The Docker Compose setup runs PostgreSQL 16 with PostGIS and the Spring Boot
backend for local development. The frontend service will be added after its
project and Dockerfile are implemented.

### Start the database and backend

1. Copy `.env.example` to `.env`.
2. Replace `POSTGRES_PASSWORD` in `.env` with a local development password.
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

## Planned Compose services

- `db`: PostgreSQL 16 + PostGIS
- `backend`: Spring Boot API
- `frontend`: React static build served by Nginx

Production-specific settings belong in `compose.prod.yaml`; real credentials
must be supplied by the deployment environment rather than committed files.
