# Backend

Spring Boot service with a readiness endpoint `GET /api/v1/health` that verifies the **real**
availability of PostgreSQL and answers HTTP `200` (UP) or HTTP `503` (DOWN).

All commands and paths below are relative to this `backend/` directory. The frontend that shows
the health status lives in its own repository, [droso-frontend](https://github.com/goodday21/droso-frontend).

## Stack

| Part      | Technology                                                        |
|-----------|-------------------------------------------------------------------|
| Backend   | Spring Boot 4.1.1, Spring MVC, Spring Data JPA (Hibernate 7), Flyway, PostgreSQL 42 |
| Toolchain | Java 21 LTS (auto-provisioned by Gradle), Gradle 9.8, Kotlin DSL |
| Tests     | JUnit 5, Mockito, real PostgreSQL 14 in-process (zonky)           |

## Why the probe really checks the database

Calling `DataSource.getConnection()` is **not** enough: a pooled connection can be stale while the
server is already down, which would produce a false `UP`. `DatabaseHealthIndicator` therefore
executes a real statement (`SELECT 1`) with a query timeout and only reports `UP` if the server
actually returned a row.

## API contract

`GET /api/v1/health`

**HTTP 200** — the database is reachable:

```json
{
  "status": "UP",
  "database": { "status": "UP", "latencyMs": 3 },
  "timestamp": "2026-10-22T08:04:43.651Z"
}
```

**HTTP 503** — the database is not reachable:

```json
{
  "status": "DOWN",
  "database": { "status": "DOWN", "latencyMs": 2000 },
  "timestamp": "2026-10-22T08:04:43.651Z"
}
```

The body never contains the JDBC URL, host, port, database name, user, exception message or stack
trace. Such details are written to the server log only. The same protection is provided by
`ApiExceptionHandler`, which converts any unexpected exception into a generic body.

The endpoint is a **readiness** check: it deliberately depends on PostgreSQL. A liveness probe
should not depend on the database.

This section is the source of truth of the contract: the frontend model
([`health.model.ts`](https://github.com/goodday21/droso-frontend/blob/main/src/app/health/health.model.ts)) follows it.

## Configuration

| Property                    | Default              | Meaning                              |
|-----------------------------|----------------------|--------------------------------------|
| `health.timeout-seconds`    | `2`                  | Query timeout of the probe statement |
| `health.query`              | `SELECT 1`           | The probe statement                  |
| `DB_URL`                    | composed from `db-local.properties` | JDBC URL              |
| `DB_USERNAME` / `DB_PASSWORD` | from `db-local.properties` | Credentials                      |
| `SERVER_PORT`               | `8080`               | HTTP port                            |

The schema is owned by the Flyway migrations (`src/main/resources/db/migration`). Hibernate never
changes it (`spring.jpa.hibernate.ddl-auto: validate`): it only checks on start-up that the entities
match the migrated tables, so a change of an entity needs a new migration.

Flyway is intentionally **not** part of the health check: the probe runs before/independently of
the migrations and does not touch any table.

## Getting started

The database configuration is not tracked by Git. Create it once after the clone:

```bash
cp src/main/resources/db-local.example.properties src/main/resources/db-local.properties
```

On Windows: `copy src\main\resources\db-local.example.properties src\main\resources\db-local.properties`

See [The database configuration](#the-database-configuration) for every property and for the
environment variables that override them.

## Running

```bash
./gradlew bootRunWithPostgres   # start a local PostgreSQL, then the service (one command)
./gradlew bootRun               # start the service only, against a database that already runs
./gradlew runPostgres           # start a local PostgreSQL only (no Docker needed)
./gradlew test                  # run all tests
```

`bootRunWithPostgres` starts (or reuses) the same server as `runPostgres` and, once it is ready,
starts the service exactly as `bootRun` does. `Ctrl+C` stops the service only: PostgreSQL keeps
running in the background and is reused by the next launch. To stop it as well, see
[`runPostgres` and repeated launches](#runpostgres-and-repeated-launches).

The tests do not require Docker: a real PostgreSQL 14 server is started in-process by
`io.zonky.test:embedded-postgres`. `HealthEndpointIntegrationTest` and
`HealthEndpointHttpIntegrationTest` verify both outcomes — `200` while the server runs and `503`
after it has been stopped.

## Manual verification

### The database configuration

The local database is configured in exactly **one** file:

```
src/main/resources/db-local.properties
```

**This file is not tracked by Git**, because it may contain the credentials of the developer's own
database. Create it before the first launch by copying the tracked template:

```bash
cp src/main/resources/db-local.example.properties src/main/resources/db-local.properties
```

On Windows:

```bat
copy src\main\resources\db-local.example.properties src\main\resources\db-local.properties
```

The template contains only placeholders (`stub`) for the database, the role and the password: put
your own values into the working copy. If the working copy is missing, the template is used as a
fallback, so the application, the launcher and Docker Compose still work on a fresh clone (with a
database, role and password named `stub`).

| Property           | Template value                                   | Meaning                    |
|--------------------|--------------------------------------------------|----------------------------|
| `POSTGRES_HOST`    | `localhost`                                      | Host of the database       |
| `POSTGRES_PORT`    | `5432`                                           | Port of the database       |
| `POSTGRES_DB`      | `stub` (placeholder)                             | Name of the database       |
| `POSTGRES_USER`    | `stub` (placeholder)                             | Role of the application    |
| `POSTGRES_PASSWORD`| `stub` (placeholder)                             | Password of the role       |

Your own values belong in the working copy only: it is ignored by Git, and nothing else in the
repository (code, tests, documentation) should repeat them.

All three consumers read that file, so they cannot drift apart:

* the **application** imports it (`spring.config.import` in `application.yml`);
* the **local launcher** (`./gradlew runPostgres`) reads it from the classpath and creates the
  role and the database it names;
* **Docker Compose** passes it to the container with `env_file` — the property names are the ones
  of the official `postgres` image, so the same file works for all three.

The environment variables `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` override the values of that
file, which is how a different database is pointed at without touching any file:

```bash
set DB_URL=jdbc:postgresql://localhost:5432/postgres
set DB_USERNAME=postgres
set DB_PASSWORD=postgres
./gradlew bootRun
```

`POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` may also
be set as environment variables; they override the values of the file for all three consumers.

The values in that file are **local development defaults**, not secrets: they belong to a
PostgreSQL that runs on the developer's own machine. Anything that is meant to be secret must be
supplied through the environment and never written into that file.

### `runPostgres` and repeated launches

`./gradlew runPostgres` owns exactly one data directory (`.postgres/data` by default, ignored by
Git). It lives outside of `build/` on purpose, so that `./gradlew clean` does not wipe the local
database — not even from under a running server. That directory is what identifies the server — the port alone is not enough, because the port may well
be taken by something else:

| Situation                                                              | Behaviour                                                                             |
|------------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| Nothing listens on the port                                            | The server is started in the owned data directory.                                     |
| The port serves a server whose `data_directory` **is** the owned one   | The running server is **reused**; the launcher prints `Reusing the PostgreSQL server…` and exits with an error only if the server cannot be reached. |
| The port serves a **foreign** server                                   | The launcher **fails** with an explicit message and **does not touch** that server.     |

The foreign server is detected with `SHOW data_directory`, i.e. from the server that actually
answers the connection — not from a successful JDBC connect, which would also succeed against
somebody else's server.

The data directory is **not** wiped between runs (`setCleanDataDirectory(false)`), so that the role
and the database survive a restart. Both are created idempotently as the `postgres` superuser, and
only after the ownership of the server has been established. Their names come from
`src/main/resources/db-local.properties`, so that the launcher and the application always point at
the same database.

Two launchers started at the same time are serialised by a lock file next to the data directory
(`.postgres/data.lock`), so that they cannot run `initdb` on the same directory at once. The
second one then finds the running server of the first one and reuses it.

Both the port and the data directory can be changed:

| Purpose         | System property      | Environment variable |
|-----------------|----------------------|----------------------|
| Port            | `-Dpostgres.port`    | `POSTGRES_PORT`      |
| Data directory  | `-Dpostgres.dataDir` | `POSTGRES_DATA_DIR`  |

`POSTGRES_PORT` overrides the port of `src/main/resources/db-local.properties` as well, so the
server the launcher starts and the URL of the application stay in sync.

```bash
./gradlew runPostgres -Dpostgres.port=55432 -Dpostgres.dataDir=.postgres/pg-55432
```

`./gradlew bootRunWithPostgres` goes through exactly the same steps, with the same properties: it
runs the launcher with `-Dpostgres.detach=true`, which returns as soon as the server is ready and
leaves it running, and then starts the service.

The server is a separate `postgres` process, so it keeps running after the launcher (or the
service) has been stopped with `Ctrl+C`; the next launch reuses it. To stop it (note that these
commands stop **every** PostgreSQL server of the machine, not only this one):

```bash
taskkill /IM postgres.exe /F    # Windows
pkill -x postgres               # Linux / macOS
```

To start completely from scratch, stop the server and delete the data directory:

```bash
rm -rf .postgres/data
```

A clone that used the old default, `build/postgres-data`, does not pick it up: the launcher starts
a new, empty server in `.postgres/data`. Stop the old server first (see above), otherwise the
launcher finds it on the port and refuses to touch it as a foreign server; then delete
`build/postgres-data`.

### The backend endpoint directly

```bash
curl -i http://localhost:8080/api/v1/health
```

`200` with `{"status":"UP",...}` while PostgreSQL runs, `503` with `{"status":"DOWN",...}` after it is
stopped. The body never contains the JDBC URL, the exception type or a stack trace.

To check the endpoint through the UI, including the *DOWN* and *not reachable* states, see
[the walkthrough of droso-frontend](https://github.com/goodday21/droso-frontend#manual-walkthrough).

### Notes for Windows

* `gradlew` is `gradlew.bat` on Windows.
* The local launcher creates the role and the database named in
  `src/main/resources/db-local.properties` as the `postgres` superuser,
  so `./gradlew bootRun` works against it without any further configuration. To point the backend at
  a different database, set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` — see
  [The database configuration](#the-database-configuration).

  With Docker available, `docker compose up -d` instead provides the same database, configured
  from the very same file.
