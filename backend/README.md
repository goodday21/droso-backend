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

### Authentication

Every endpoint needs an access token, except these:

| Endpoint                          | Why it is open                                            |
|-----------------------------------|-----------------------------------------------------------|
| `GET /api/v1/health`              | The public health contract                                |
| `POST /api/v1/auth/register`      | Anyone may register (unless the registration is disabled) |
| `POST /api/v1/auth/login`         | Issues the token                                          |
| `GET /actuator/health`            | Probe of the platform, without details                    |

The token is obtained with [`POST /api/v1/auth/login`](#logging-in) and sent in the header
`Authorization: Bearer <accessToken>`, never in the URL (a query parameter `access_token` is
ignored). The API is stateless: no session, no cookie, no CSRF token; HTTP Basic and form login
are off.

* **HTTP 401** `UNAUTHORIZED` without a token, or with a token that is invalid, expired, revoked or
  of a deleted user, with `WWW-Authenticate: Bearer` (and `error="invalid_token"` when a token was
  sent). The reason is not told.
* **HTTP 403** `FORBIDDEN` with a valid token whose user lacks the permission of the endpoint (see
  below), and for the rest of Actuator (`/actuator/metrics`, ...), which is closed to everyone until
  the deployment decides who reads it.
* An unknown URL answers `401` without a token and `404` with one.

The token is a JWT signed with HS256 and a server secret (`DROSO_JWT_SECRET`), valid for 30 minutes
(`droso.jwt.access-token-ttl`). It is signed, not encrypted: its claims can be read by anyone who
holds it, and carry nothing secret.

| Claim   | Value                                                                        |
|---------|------------------------------------------------------------------------------|
| `sub`   | The id of the user (as a string), never the email                           |
| `email` | The email, for the client                                                    |
| `role`  | The role at the login, for the client only: the server uses the current one |
| `iat` / `exp` | Issued at / expires at (seconds)                                     |
| `iss`   | `droso-backend` (`droso.jwt.issuer`)                                         |
| `jti`   | A random id of the token                                                    |
| `ver`   | The token version of the user (`users.token_version`)                       |

On every request the server checks the signature (HS256 only: `alg: none` and any other algorithm
are rejected), `exp`/`nbf` with a leeway of 60 seconds, `iss`, and then loads the user by `sub`:
a deleted user, or a `ver` that differs from `users.token_version`, is rejected at once although the
token has not expired. Incrementing `token_version` therefore revokes every token of a user. The
authorities come from the role in the database, not from the claim.

#### Roles and permissions

Every user has one role. The endpoints require a *permission*, never a role; which role has which
permission is fixed in the code (`org.example.user.Role`), and the roles are nested:

| Permission      | `USER` | `MANAGER` | `ADMIN` | Needed by                    |
|-----------------|:------:|:---------:|:-------:|------------------------------|
| `DEFECT_READ`   |   ✓    |     ✓     |    ✓    | `GET /api/v1/defects/{id}`   |
| `DEFECT_CREATE` |   ✓    |     ✓     |    ✓    | `POST /api/v1/defects`       |
| `DEFECT_UPDATE` |        |     ✓     |    ✓    | (the editing of the defects, to come) |
| `USER_MANAGE`   |        |           |    ✓    | (the administration of the users, to come) |

A change of the role in the database applies to the next request, without a new token.
[`GET /api/v1/auth/me`](#the-current-user) lists the permissions of the current user, so that a
client shows only what the user may do. The permission is checked after the body has been read and
validated: an invalid body answers `400` even to a user who lacks the permission.

The registration only creates users with the role `USER`. The first administrator is created on
start-up from `DROSO_BOOTSTRAP_ADMIN_EMAIL` and `DROSO_BOOTSTRAP_ADMIN_PASSWORD` (see
[Configuration](#configuration)).

### Health

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

Needs the permission `DEFECT_CREATE` (every role has it). The author of the defect is the user of
the token.

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
  "createdBy": 3,
  "createdAt": "2026-10-27T09:15:30.123456Z",
  "updatedAt": "2026-10-27T09:15:30.123456Z"
}
```

Every field of the response is always present (`null` when not set). `id`, `status` (always `NEW`),
`createdBy` (the id of the reporting user), `createdAt` and `updatedAt` (equal for a new defect) are
chosen by the server. `createdBy` is `null` only for the defects created before the authors were
recorded.

**Unknown properties are rejected**, including `id`, `status`, `createdBy`, `createdAt` and `updatedAt`:
`400 VALIDATION_ERROR` with `"fieldErrors": {"status": "is not allowed"}`. A client that sends them
must not believe it has set them, and a misspelt `"priorty"` must not silently fall back to `MEDIUM`.
Only the first unknown property is reported. An enum is not read from its index either
(`"priority": 2` is rejected).

### Getting a defect

`GET /api/v1/defects/{id}`, needs the permission `DEFECT_READ` (every role has it).

**HTTP 200** with the stored defect, in the same format as the response of `POST` above. Nothing is
written: the request runs in a read-only transaction.

**HTTP 404** `RESOURCE_NOT_FOUND` if there is no defect with this id. The body names neither the
resource nor the id.

**HTTP 400** `VALIDATION_ERROR` with `"fieldErrors": {"id": "has an invalid value"}` if the id is not
in its canonical form: a positive decimal number of ASCII digits, without sign, leading zeros or
whitespace, at most `9223372036854775807`. So `abc`, `1.5`, `0`, `-1` are rejected, and so are
`026`, `+26`, `0x1A`, `%231A` (`#1A`) and `%2026`, which the default conversion of Spring would
silently read as the defect 26 (`org.example.web.CanonicalIdEditor`).

Two cases are decided before this check:

* A semicolon, plain or encoded (`/api/v1/defects/26;x=1`, `%3B26`), is rejected by the firewall
  of Spring Security with `400 BAD_REQUEST`. Without it, the servlet container would remove
  `;x=1` as a path parameter and answer the defect 26.
* An encoded slash, backslash or NUL (`%2F`, `%5C`, `%00`) is rejected by Tomcat with `400` and its
  bare HTML error page, not with the body below.

`GET /api/v1/defects` (the list) does not exist and answers `405 METHOD_NOT_ALLOWED`;
`/api/v1/defects/` and `/api/v1/defects/26/x` answer `404`.

### Registering a user

`POST /api/v1/auth/register` with `Content-Type: application/json`:

```json
{ "email": "qwe.qwe+1@gmail.com", "password": "Sup3r-Secret!" }
```

**HTTP 201** with the user (no `Location`: there is no resource of a single user yet). Neither the
password nor its hash is ever returned:

```json
{ "id": 1, "email": "qwe.qwe+1@gmail.com", "role": "USER", "createdAt": "2026-10-27T10:15:30.123456Z" }
```

* **The role is always `USER`.** A request that sends `role` (or any other unknown property) answers
  `400` with `"role": "is not allowed"`.
* **email** — the login. It is stripped of the invisible characters around it and lowered in case
  before it is validated and stored, so `Qwe@Gmail.com` and `qwe@gmail.com` are one account; the part
  after `+` is kept, so `qwe+1@gmail.com` is another one. Valid: a local part of `a-z 0-9 . _ % + -`
  (at most 64 characters, no dot at its ends or twice in a row), a domain of at least two labels of
  `a-z 0-9 -` ending with a top-level domain of letters, at most 254 characters in all. Quoted local
  parts, IP literals and non-ASCII addresses are rejected (`org.example.user.EmailAddress`).
* **password** — 8..64 characters, printable ASCII only (Latin letters, digits, punctuation; no space),
  with at least one upper case letter, one lower case letter, one digit and one punctuation character.
  It is never stripped or otherwise changed. Every violated rule is reported in
  `fieldErrors.password`, joined with `; ` (`org.example.user.PasswordPolicy`).
* The password is stored as a BCrypt hash of cost 12 tagged with its algorithm
  (`{bcrypt}$2a$12$...`, `org.example.security.PasswordEncoderConfig`).
* **HTTP 409** `EMAIL_ALREADY_REGISTERED` with `"fieldErrors": {"email": "is already registered"}` if
  an active user has the email. The address of a soft-deleted user (`users.deleted_at` set) may
  register again, as a new user.
* **HTTP 403** `REGISTRATION_DISABLED` if `droso.auth.registration-enabled` is `false`. The body is
  validated first, so an invalid body still answers `400`.

### Logging in

`POST /api/v1/auth/login` with `Content-Type: application/json`:

```json
{ "email": "qwe.qwe+1@gmail.com", "password": "Sup3r-Secret!" }
```

**HTTP 200** with `Cache-Control: no-store`:

```json
{ "accessToken": "eyJraWQiOi...", "tokenType": "Bearer", "expiresIn": 1800 }
```

* **email** is normalized like on the registration (`Qwe.Qwe+1@Gmail.com` logs in as well); the
  **password** is compared exactly as given.
* **HTTP 401** `INVALID_CREDENTIALS` if the email has no active user, the user is deleted, or the
  password does not match. The three cases are indistinguishable, in the body and in the time:
  an unknown email is still checked against a dummy BCrypt hash.
* **HTTP 400** `VALIDATION_ERROR` only for a missing email or password, an email longer than 254 or
  a password longer than 64 characters. The rules of a new password are not applied: a password
  accepted by an older policy still logs in.
* There is no refresh token yet (TASK-010): after `expiresIn` seconds the client logs in again.

### The current user

`GET /api/v1/auth/me` with a token answers **HTTP 200** with the user, as currently stored (the
role may differ from the claim of the token), and the [permissions](#roles-and-permissions) of
that role, in a fixed order:

```json
{
  "id": 1,
  "email": "qwe.qwe+1@gmail.com",
  "role": "USER",
  "permissions": ["DEFECT_READ", "DEFECT_CREATE"],
  "createdAt": "2026-10-27T10:15:30.123456Z"
}
```

The permissions are a hint for the interface; the server checks them on every request anyway.

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
| 400  | `BAD_REQUEST`         | The URL is rejected by the firewall of Spring Security (e.g. a `;`)                     |
| 401  | `UNAUTHORIZED`        | No access token, or an invalid one ([Authentication](#authentication))                  |
| 401  | `INVALID_CREDENTIALS` | A failed login                                                                         |
| 403  | `FORBIDDEN`           | A valid token that is not enough                                                       |
| 403  | `REGISTRATION_DISABLED` | A registration while `droso.auth.registration-enabled` is `false`                    |
| 404  | `RESOURCE_NOT_FOUND`  | `ResourceNotFoundException` is thrown, or the URL is unknown                            |
| 409  | `EMAIL_ALREADY_REGISTERED` | A registration with the email of an active user; `fieldErrors.email` is set        |
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
  Spring MVC. The errors of Spring Security, which runs before, are written in the same format by
  `org.example.security` (`401`, `403`, the `400` of the firewall, and a `500 INTERNAL_ERROR` when
  the database fails while a token is checked). An error raised by Tomcat itself (an invalid URL)
  gets the default body of Spring Boot, without message or stack trace.

## Configuration

| Property                    | Default              | Meaning                              |
|-----------------------------|----------------------|--------------------------------------|
| `health.timeout-seconds`    | `2`                  | Query timeout of the probe statement |
| `health.query`              | `SELECT 1`           | The probe statement                  |
| `DB_URL`                    | composed from `db-local.properties` | JDBC URL              |
| `DB_USERNAME` / `DB_PASSWORD` | from `db-local.properties` | Credentials                      |
| `SERVER_PORT`               | `8080`               | HTTP port                            |
| `droso.auth.registration-enabled` (`DROSO_AUTH_REGISTRATION_ENABLED`) | `true` | Whether anyone may register (always as `USER`) |
| `DROSO_JWT_SECRET` (`droso.jwt.secret`) | **none, required** | Secret of the access tokens: Base64 of at least 32 random bytes |
| `droso.jwt.issuer`          | `droso-backend`      | Claim `iss` of the tokens            |
| `droso.jwt.access-token-ttl` | `30m`               | Lifetime of an access token          |
| `DROSO_BOOTSTRAP_ADMIN_EMAIL` / `DROSO_BOOTSTRAP_ADMIN_PASSWORD` | none | The first administrator, created on start-up when there is none |

`DROSO_JWT_SECRET` has no default anywhere, on purpose: whoever knows the secret can forge a token
of any user, so it must never be in the repository. The application does not start without it, or
with a value that is not Base64 or shorter than 32 bytes, and the message says what to set.
Generate one per environment and keep it out of Git:

```bash
openssl rand -base64 32
```

Changing the secret invalidates every issued token: all users have to log in again.

The registration never creates an administrator. On every start-up, if no active user has the role
`ADMIN`:

* with `DROSO_BOOTSTRAP_ADMIN_EMAIL` and `DROSO_BOOTSTRAP_ADMIN_PASSWORD` set, that user is created
  with the role `ADMIN` (the email and the password follow the rules of a registration);
* without them, the application starts with a warning in the log, and nobody can manage the users;
* with only one of them, an invalid value, or the **email of an existing active user**, the
  application **does not start**. An existing user is never made an administrator this way: anyone
  could have registered that email before.

Once an administrator exists, both variables are ignored and may be removed. A deleted
administrator does not count.

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

The service needs the secret of the access tokens in its environment (see
[Configuration](#configuration)); set it once per terminal:

```bash
export DROSO_JWT_SECRET="$(openssl rand -base64 32)"      # bash
$env:DROSO_JWT_SECRET = "<output of openssl rand -base64 32>"   # PowerShell
```

In IntelliJ IDEA, add it to the environment variables of the run configuration. A new secret on
every start only means logging in again.

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

### Registering and logging in

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/register   -H "Content-Type: application/json"   -d '{"email": "Qwe.Qwe+1@Gmail.com", "password": "Sup3r-Secret!"}'
curl -i -X POST http://localhost:8080/api/v1/auth/login   -H "Content-Type: application/json"   -d '{"email": "qwe.qwe+1@gmail.com", "password": "Sup3r-Secret!"}'
```

`201` with `"email":"qwe.qwe+1@gmail.com"` and `"role":"USER"`, then `200` with the token. Keep it
for the requests below:

```bash
TOKEN=<accessToken of the response>
curl -i http://localhost:8080/api/v1/auth/me -H "Authorization: Bearer $TOKEN"
```

The same registration again answers `409 EMAIL_ALREADY_REGISTERED`; `"password": "secret"` answers
`400` with every violated rule in `fieldErrors.password`. A wrong password at the login answers
`401 INVALID_CREDENTIALS`. The row holds the hash, never the password:

```sql
SELECT id, email, role_code, left(password_hash, 15), token_version, deleted_at, created_at, updated_at FROM users;
```

`UPDATE users SET token_version = token_version + 1` makes the next request with the token answer
`401`; so does `deleted_at = now()`. `/me` lists `"permissions":["DEFECT_READ","DEFECT_CREATE"]`;
after `UPDATE users SET role_code = 'MANAGER'` the same token lists `DEFECT_UPDATE` too.

To get an administrator, start the application once with `DROSO_BOOTSTRAP_ADMIN_EMAIL=admin@example.com`
and `DROSO_BOOTSTRAP_ADMIN_PASSWORD=Adm1n-Secret!` on a database without one: the log says
`Created the administrator with the id ...`, and the login with them answers a token whose `/me`
has `"role":"ADMIN"`. Without the variables the log warns `There is no administrator`.

### Creating a defect

```bash
curl -i -X POST http://localhost:8080/api/v1/defects   -H "Authorization: Bearer $TOKEN"   -H "Content-Type: application/json"   -d '{"title": "Login fails", "priority": "HIGH"}'
```

Without the header it answers `401 UNAUTHORIZED` and stores nothing.

`201` with `Location: /api/v1/defects/1` and the defect with `"status":"NEW"`. The row is in the
database (`psql -h localhost -U <POSTGRES_USER> <POSTGRES_DB>`, see `db-local.properties`):

```sql
SELECT id, title, priority, status, created_by, created_at, updated_at FROM defects;
```

`created_by` (and `createdBy` in the response) is the id of the user of the token.

`'{"title": "   "}'` answers `400 VALIDATION_ERROR` with `fieldErrors.title`, `'{"title": "Login
fails", "status": "CLOSED"}'` answers `400` with `"status": "is not allowed"`, `'{"title": '`
answers `400 MALFORMED_REQUEST`.

### Getting a defect

```bash
curl -i http://localhost:8080/api/v1/defects/1 -H "Authorization: Bearer $TOKEN"
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
