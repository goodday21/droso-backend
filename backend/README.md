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
trace. Such details are written to the server log only. An unexpected failure of the endpoint
answers `500` with the [error format](#error-responses) below.

The endpoint is a **readiness** check: it deliberately depends on PostgreSQL. A liveness probe
should not depend on the database.

This section is the source of truth of the contract: the frontend model
([`health.model.ts`](https://github.com/goodday21/droso-frontend/blob/main/src/app/health/health.model.ts)) follows it.

### Creating a defect

`POST /api/v1/defects` with `Content-Type: application/json`:

```json
{
  "title": "Profile changes are not saved",
  "description": "The changes are lost after editing the profile",
  "stepsToReproduce": "1. Open the profile\n2. Change the name\n3. Click Save",
  "expectedResult": "The new name is saved",
  "actualResult": "The old name is shown",
  "priority": "HIGH"
}
```

| Field              | Rule                                                                         |
|--------------------|------------------------------------------------------------------------------|
| `title`            | Required; 5..120 characters after stripping the surrounding invisible characters, stored stripped |
| `description`      | Optional, at most 2000 characters                                            |
| `stepsToReproduce` | Optional, at most 5000 characters                                            |
| `expectedResult`   | Optional, at most 2000 characters                                            |
| `actualResult`     | Optional, at most 2000 characters                                            |

*Invisible* are the whitespace, the no-break spaces (U+00A0, U+2007, U+202F) and the other space
separators, and the zero-width characters (U+200B, U+200C, U+200D, U+2060, U+FEFF, …): a title of
five no-break spaces is blank. An optional field that is invisible as a whole is stored as `null`;
any other is stored exactly as sent, its surrounding whitespace and line breaks included.
| `priority`         | Optional: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` (exact case); `MEDIUM` when absent or `null` |

Lengths are counted in characters (Unicode code points), as by the `VARCHAR` columns: an emoji is
one character.

**HTTP 201** with the header `Location: /api/v1/defects/{id}` (relative) and the stored defect:

```json
{
  "id": 1,
  "title": "Profile changes are not saved",
  "description": "The changes are lost after editing the profile",
  "stepsToReproduce": "1. Open the profile\n2. Change the name\n3. Click Save",
  "expectedResult": "The new name is saved",
  "actualResult": "The old name is shown",
  "priority": "HIGH",
  "status": "NEW",
  "createdAt": "2026-10-27T09:15:30.123456Z",
  "updatedAt": "2026-10-27T09:15:30.123456Z"
}
```

Every field of the response is always present (`null` when not set). `id`, `status` (always `NEW`),
`createdAt` and `updatedAt` (equal for a new defect) are chosen by the server.

**Unknown properties are rejected**, including `id`, `status`, `createdAt` and `updatedAt`:
`400 VALIDATION_ERROR` with `"fieldErrors": {"status": "is not allowed"}`. A client that sends them
must not believe it has set them, and a misspelt `"priorty"` must not silently fall back to `MEDIUM`.
Only the first unknown property is reported. An enum is not read from its index either
(`"priority": 2` is rejected).

### Getting a defect

`GET /api/v1/defects/{id}`

**HTTP 200** with the stored defect, in the same format as the response of `POST` above. Nothing is
written: the request runs in a read-only transaction.

**HTTP 404** `RESOURCE_NOT_FOUND` if there is no defect with this id. The body names neither the
resource nor the id.

**HTTP 400** `VALIDATION_ERROR` with `"fieldErrors": {"id": "has an invalid value"}` if the id is not
in its canonical form: a positive decimal number of ASCII digits, without sign, leading zeros or
whitespace, at most `9223372036854775807`. So `abc`, `1.5`, `0`, `-1` are rejected, and so are
`026`, `+26`, `0x1A`, `%231A` (`#1A`) and `%2026`, which the default conversion of Spring would
silently read as the defect 26 (`org.example.web.CanonicalIdEditor`).

Two cases are decided by the servlet container, before this check:

* A path parameter is removed from the URL, as the Servlet specification provides:
  `/api/v1/defects/26;x=1` is the defect 26.
* An encoded slash, backslash or NUL (`%2F`, `%5C`, `%00`) is rejected by Tomcat with `400` and its
  bare HTML error page, not with the body below.

`GET /api/v1/defects` (the list) does not exist and answers `405 METHOD_NOT_ALLOWED`;
`/api/v1/defects/` and `/api/v1/defects/26/x` answer `404`.

### Error responses

Every error of the API (except the `503` of the health endpoint above) has the same body:

```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "fieldErrors": { "title": "must not be blank" }
}
```

| HTTP | `code`                | When                                                                                   |
|------|-----------------------|----------------------------------------------------------------------------------------|
| 400  | `VALIDATION_ERROR`    | A Bean Validation constraint of the body or of a parameter is violated, a parameter is missing or has the wrong type, a property of the body is unknown or has a value of the wrong type (e.g. an unknown enum value) |
| 400  | `MALFORMED_REQUEST`   | The body cannot be read: invalid JSON, no body, a body that is not a JSON object |
| 404  | `RESOURCE_NOT_FOUND`  | `ResourceNotFoundException` is thrown, or the URL is unknown                            |
| 500  | `INTERNAL_ERROR`      | Any unexpected exception                                                               |
| other| name of the status    | Other errors of Spring MVC, e.g. `405 METHOD_NOT_ALLOWED`, `415 UNSUPPORTED_MEDIA_TYPE` |

* `code` and `message` are stable; `message` is fixed per code and never contains the text of an
  exception. The details are written to the server log (`ERROR` with the stack trace for `5xx`,
  `DEBUG` for `4xx`).
* `fieldErrors` is always present, empty unless the request failed validation. The keys are the
  names of the fields or parameters, sorted; several errors of one field are joined with `; `.
  The messages are the ones of the constraints, always in English (`spring.web.locale-resolver:
  fixed`); a failed conversion is reported as `has an invalid value`, never with Java types or the
  rejected value, and an unknown property of the body as `is not allowed`.
* The handler is `org.example.web.error.ApiExceptionHandler`. It covers the requests that reach
  Spring MVC; an error raised before (for instance by Tomcat for an invalid URL) gets the default
  body of Spring Boot, without message or stack trace.

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

### Creating a defect

```bash
curl -i -X POST http://localhost:8080/api/v1/defects   -H "Content-Type: application/json"   -d '{"title": "Login fails", "priority": "HIGH"}'
```

`201` with `Location: /api/v1/defects/1` and the defect with `"status":"NEW"`. The row is in the
database (`psql -h localhost -U <POSTGRES_USER> <POSTGRES_DB>`, see `db-local.properties`):

```sql
SELECT id, title, priority, status, created_at, updated_at FROM defects;
```

`'{"title": "   "}'` answers `400 VALIDATION_ERROR` with `fieldErrors.title`, `'{"title": "Login
fails", "status": "CLOSED"}'` answers `400` with `"status": "is not allowed"`, `'{"title": '`
answers `400 MALFORMED_REQUEST`.

### Getting a defect

```bash
curl -i http://localhost:8080/api/v1/defects/1
```

`200` with the defect created above. `/api/v1/defects/999999` answers `404 RESOURCE_NOT_FOUND`,
`/api/v1/defects/abc` and `/api/v1/defects/0x1` answer `400 VALIDATION_ERROR` with
`fieldErrors.id`. The row in the database is unchanged by the GET.

On Windows, `curl` does not pass a non-ASCII `-d` argument as UTF-8, so a Cyrillic title arrives as
invalid UTF-8 and answers `400 MALFORMED_REQUEST`. Put such a body into a UTF-8 file and send it
with `--data-binary @defect.json`.

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
