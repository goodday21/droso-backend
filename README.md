# project-with-cline

Spring Boot service with a readiness endpoint `GET /api/v1/health` that verifies the **real**
availability of PostgreSQL and answers HTTP `200` (UP) or HTTP `503` (DOWN).

## Stack

| Part      | Technology                                                        |
|-----------|-------------------------------------------------------------------|
| Backend   | Spring Boot 4.1.1, Spring MVC, Spring JDBC, Flyway, PostgreSQL 42 |
| Toolchain | Java 21 LTS (auto-provisioned by Gradle), Gradle 9.8, Kotlin DSL |
| Frontend  | Angular 22, Karma/Jasmine                                          |
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

## Configuration

| Property                    | Default              | Meaning                              |
|-----------------------------|----------------------|--------------------------------------|
| `health.timeout-seconds`    | `2`                  | Query timeout of the probe statement |
| `health.query`              | `SELECT 1`           | The probe statement                  |
| `DB_URL`                    | `jdbc:postgresql://localhost:5432/appdb` | JDBC URL   |
| `DB_USERNAME` / `DB_PASSWORD` | `app` / `app`      | Credentials                          |
| `SERVER_PORT`               | `8080`               | HTTP port                            |

Flyway is intentionally **not** part of the health check: the probe runs before/independently of
the migrations and does not touch any table.

## Backend

```bash
./gradlew bootRun          # start the service
./gradlew test             # run all tests
```

The tests do not require Docker: a real PostgreSQL 14 server is started in-process by
`io.zonky.test:embedded-postgres`. `HealthEndpointIntegrationTest` and
`HealthEndpointHttpIntegrationTest` verify both outcomes — `200` while the server runs and `503`
after it has been stopped.

## Frontend

```bash
cd frontend
npm install
npm start                  # dev server on http://localhost:4200, proxies /api to :8080
npm test                   # unit tests (ChromeHeadless)
npm run build
```

`HealthApiService` maps the HTTP status to three explicit outcomes:

* `up` — HTTP 200, the database is reachable;
* `down` — HTTP 503, the database is not reachable (a normal answer, not a transport error);
* `unreachable` — the backend itself could not be reached (network error).

## Manual verification

### Frontend tests

```bash
cd frontend
npm install                  # once
npm test                     # single run, ChromeHeadless
npm test -- --watch          # watch mode: re-runs on every file change
```

In watch mode Karma serves a UI at <http://localhost:9876/> — open it, tick the spec files you
want to see, and use the "Debug" button to inspect the DOM of a failing expectation.

To run a single spec, narrow the pattern, e.g. `ng test --watch --include='**/health-api.service.spec.ts'`.

### The frontend itself

Three terminals:

```bash
# 1) a real PostgreSQL on :5432 (no Docker needed)
./gradlew runPostgres

# 2) the backend on :8080
./gradlew bootRun

# 3) the dev server on :4200
cd frontend && npm start
```

Open <http://localhost:4200/>. The panel shows a green **UP** badge with the database latency.

Now break the database and press **Refresh** in the panel:

```bash
# stops PostgreSQL — the panel must turn red with HTTP status 503
taskkill /IM postgres.exe /F
```

Restart it with `./gradlew runPostgres` and press **Refresh** again — the panel must return to green.
This proves the UI reacts to a genuinely broken database rather than to a static mock.

To see the third state, stop the backend (`Ctrl+C` in terminal 2) and press **Refresh**: the panel
shows *not reachable* instead of *DOWN*, because the service itself no longer answers.

### `runPostgres` and repeated launches

`./gradlew runPostgres` owns exactly one data directory (`build/postgres-data` by default). That
directory is what identifies the server — the port alone is not enough, because the port may well
be taken by something else:

| Situation                                                              | Behaviour                                                                             |
|------------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| Nothing listens on the port                                            | The server is started in the owned data directory.                                     |
| The port serves a server whose `data_directory` **is** the owned one   | The running server is **reused**; the launcher prints `Reusing the PostgreSQL server…` and exits with an error only if the server cannot be reached. |
| The port serves a **foreign** server                                   | The launcher **fails** with an explicit message and **does not touch** that server.     |

The foreign server is detected with `SHOW data_directory`, i.e. from the server that actually
answers the connection — not from a successful JDBC connect, which would also succeed against
somebody else's server.

The data directory is **not** wiped between runs (`setCleanDataDirectory(false)`), so the `app`
role and the `appdb` database survive a restart. Both are created idempotently as the `postgres`
superuser, and only after the ownership of the server has been established.

Two launchers started at the same time are serialised by a lock file next to the data directory
(`build/postgres-data.lock`), so that they cannot run `initdb` on the same directory at once. The
second one then finds the running server of the first one and reuses it.

Both the port and the data directory can be changed:

| Purpose         | System property      | Environment variable |
|-----------------|----------------------|----------------------|
| Port            | `-Dpostgres.port`    | `POSTGRES_PORT`      |
| Data directory  | `-Dpostgres.dataDir` | `POSTGRES_DATA_DIR`  |

```bash
./gradlew runPostgres -Dpostgres.port=55432 -Dpostgres.dataDir=build/pg-55432
```

To start completely from scratch, delete the data directory:

```bash
rm -rf build/postgres-data
```

### The backend endpoint directly

```bash
curl -i http://localhost:8080/api/v1/health
```

`200` with `{"status":"UP",...}` while PostgreSQL runs, `503` with `{"status":"DOWN",...}` after it is
stopped. The body never contains the JDBC URL, the exception type or a stack trace.

### Notes for Windows

* In PowerShell, `npm` resolves to `npm.ps1`, which the default execution policy blocks. Use
  `npm.cmd` instead (`npm.cmd test`), or run the commands in `cmd.exe`.
* `gradlew` is `gradlew.bat` on Windows.
* `./gradlew runPostgres` starts PostgreSQL with the default user `postgres` and the database
  `postgres`. To point the backend at it, override the defaults of `application.yml`:

  ```bash
  set DB_URL=jdbc:postgresql://localhost:5432/postgres
  set DB_USERNAME=postgres
  set DB_PASSWORD=postgres
  ./gradlew bootRun
  ```

  With Docker available, `docker compose up -d` instead provides the `appdb`/`app`/`app` database
  that `application.yml` expects by default.

  The launcher itself already creates the `appdb`/`app`/`app` database that `application.yml` expects
  by default, so `./gradlew bootRun` works against it without any further configuration.