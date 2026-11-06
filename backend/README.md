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
| `POST /api/v1/auth/refresh`       | Authenticated by the refresh cookie ([Refreshing](#refreshing-the-access-token)) |
| `POST /api/v1/auth/logout`        | Authenticated by the refresh cookie ([Logging out](#logging-out)) |
| `GET /actuator/health`            | Probe of the platform, without details                    |

The token is obtained with [`POST /api/v1/auth/login`](#logging-in) and sent in the header
`Authorization: Bearer <accessToken>`, never in the URL (a query parameter `access_token` is
ignored). The API is stateless: no session, no CSRF token; HTTP Basic and form login are off. The
only cookie is the refresh token, which gets a new access token when the old one expires
([Refreshing](#refreshing-the-access-token)); it is sent to `/api/v1/auth` only and never
authenticates any other request.

* **HTTP 401** `UNAUTHORIZED` without a token, or with a token that is invalid, expired, revoked or
  of a deleted user, with `WWW-Authenticate: Bearer` (and `error="invalid_token"` when a token was
  sent). The reason is not told.
* **HTTP 403** `FORBIDDEN` with a valid token whose user lacks the permission of the endpoint (see
  below), and for the rest of Actuator (`/actuator/metrics`, ...), which is closed to everyone until
  the deployment decides who reads it.
* An unknown URL answers `401` without a token and `404` with one.

The token is a JWT signed with HS256 and a server secret (`DROSO_JWT_SECRET`), valid for 15 minutes
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
token has not expired. Incrementing `token_version` therefore revokes every token of a user, the
refresh tokens included (a deletion, [`/logout-all`](#logging-out)). The authorities come from the
role in the database, not from the claim.

A request from a browser page of another origin is allowed (CORS, with credentials) only from the
origins of `droso.auth.allowed-origins` (`DROSO_AUTH_ALLOWED_ORIGINS`, by default
`http://localhost:4200`); from any other origin every endpoint under `/api` answers
`403 FORBIDDEN`, a preflight included.

#### Roles and permissions

Every user has one role. The endpoints require a *permission*, never a role; which role has which
permission is fixed in the code (`org.example.user.Role`), and the roles are nested:

| Permission      | `USER` | `MANAGER` | `ADMIN` | Needed by                    |
|-----------------|:------:|:---------:|:-------:|------------------------------|
| `TICKET_READ`   |   ✓    |     ✓     |    ✓    | `GET /api/v1/tickets/{id}`   |
| `TICKET_CREATE` |   ✓    |     ✓     |    ✓    | `POST /api/v1/tickets`       |
| `TICKET_UPDATE` |        |     ✓     |    ✓    | (the editing of the tickets, to come) |
| `USER_MANAGE`   |        |           |    ✓    | [`/api/v1/users`](#managing-users)  |

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

### Creating a ticket

A ticket is a feature or a bug (`type`). The endpoints were `/api/v1/defects` and the permissions
`DEFECT_*` before the rename; the old paths no longer exist.

`POST /api/v1/tickets` with `Content-Type: application/json`:

```json
{
  "title": "Profile changes are not saved",
  "description": "The changes are lost after editing the profile",
  "stepsToReproduce": "1. Open the profile\n2. Change the name\n3. Click Save",
  "expectedResult": "The new name is saved",
  "actualResult": "The old name is shown",
  "priority": "HIGH",
  "type": "BUG"
}
```

| Field              | Rule                                                                         |
|--------------------|------------------------------------------------------------------------------|
| `title`            | Required; 5..120 characters after stripping the surrounding invisible characters, stored stripped |
| `description`      | Optional, at most 2000 characters                                            |
| `stepsToReproduce` | Optional, at most 5000 characters                                            |
| `expectedResult`   | Optional, at most 2000 characters                                            |
| `actualResult`     | Optional, at most 2000 characters                                            |
| `priority`         | Optional: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` (exact case); `MEDIUM` when absent or `null` |
| `type`             | Optional: `FEATURE`, `BUG` (exact case); `FEATURE` when absent or `null`      |

*Invisible* are the whitespace, the no-break spaces (U+00A0, U+2007, U+202F) and the other space
separators, and the zero-width characters (U+200B, U+200C, U+200D, U+2060, U+FEFF, …): a title of
five no-break spaces is blank. An optional field that is invisible as a whole is stored as `null`;
any other is stored exactly as sent, its surrounding whitespace and line breaks included.

Lengths are counted in characters (Unicode code points), as by the `VARCHAR` columns: an emoji is
one character.

Needs the permission `TICKET_CREATE` (every role has it). The author of the ticket is the user of
the token.

**HTTP 201** with the header `Location: /api/v1/tickets/{id}` (relative) and the stored ticket:

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
  "type": "BUG",
  "createdBy": 3,
  "createdAt": "2026-10-27T09:15:30.123456Z",
  "updatedAt": "2026-10-27T09:15:30.123456Z"
}
```

Every field of the response is always present (`null` when not set). `id`, `status` (always `NEW`),
`createdBy` (the id of the reporting user), `createdAt` and `updatedAt` (equal for a new ticket) are
chosen by the server. `createdBy` is `null` only for the tickets created before the authors were
recorded.

`status` is one of `NEW`, `IN_DEVELOPMENT`, `IN_REVIEW`, `READY_FOR_QA`, `IN_QA`, `WAITING_FOR_FIX`,
`READY_TO_RELEASE`, `RELEASED`, `DECLINED`. A new ticket is always `NEW`; the API cannot change the
status yet, and the transitions between the statuses are not defined yet. A client should be ready
for every value of the list. The tickets created before the type existed are `FEATURE`.

**Unknown properties are rejected**, including `id`, `status`, `createdBy`, `createdAt` and `updatedAt`:
`400 VALIDATION_ERROR` with `"fieldErrors": {"status": "is not allowed"}`. A client that sends them
must not believe it has set them, and a misspelt `"priorty"` must not silently fall back to `MEDIUM`.
Only the first unknown property is reported. An enum is not read from its index either
(`"priority": 2` is rejected).

### Getting a ticket

`GET /api/v1/tickets/{id}`, needs the permission `TICKET_READ` (every role has it).

**HTTP 200** with the stored ticket, in the same format as the response of `POST` above. Nothing is
written: the request runs in a read-only transaction.

**HTTP 404** `RESOURCE_NOT_FOUND` if there is no ticket with this id, or it is deleted (tickets are
soft-deleted, `tickets.deleted_at`; the API cannot delete one yet). The body names neither the
resource nor the id.

**HTTP 400** `VALIDATION_ERROR` with `"fieldErrors": {"id": "has an invalid value"}` if the id is not
in its canonical form: a positive decimal number of ASCII digits, without sign, leading zeros or
whitespace, at most `9223372036854775807`. So `abc`, `1.5`, `0`, `-1` are rejected, and so are
`026`, `+26`, `0x1A`, `%231A` (`#1A`) and `%2026`, which the default conversion of Spring would
silently read as the ticket 26 (`org.example.web.CanonicalIdEditor`).

Two cases are decided before this check:

* A semicolon, plain or encoded (`/api/v1/tickets/26;x=1`, `%3B26`), is rejected by the firewall
  of Spring Security with `400 BAD_REQUEST`. Without it, the servlet container would remove
  `;x=1` as a path parameter and answer the ticket 26.
* An encoded slash, backslash or NUL (`%2F`, `%5C`, `%00`) is rejected by Tomcat with `400` and its
  bare HTML error page, not with the body below.

`GET /api/v1/tickets` (the list) does not exist and answers `405 METHOD_NOT_ALLOWED`;
`/api/v1/tickets/` and `/api/v1/tickets/26/x` answer `404`.

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
{ "accessToken": "eyJraWQiOi...", "tokenType": "Bearer", "expiresIn": 900 }
```

and the refresh token as a cookie, never in the body:

```
Set-Cookie: droso_refresh=<43 characters>; Path=/api/v1/auth; Max-Age=604800; Expires=...; Secure; HttpOnly; SameSite=Strict
```

* **email** is normalized like on the registration (`Qwe.Qwe+1@Gmail.com` logs in as well); the
  **password** is compared exactly as given.
* **HTTP 401** `INVALID_CREDENTIALS` if the email has no active user, the user is deleted, or the
  password does not match. The three cases are indistinguishable, in the body and in the time:
  an unknown email is still checked against a dummy BCrypt hash.
* **HTTP 400** `VALIDATION_ERROR` only for a missing email or password, an email longer than 254 or
  a password longer than 64 characters. The rules of a new password are not applied: a password
  accepted by an older policy still logs in.
* Every login starts a new *family* of refresh tokens (one per device or browser): logging in on a
  phone does not end the login on a laptop.

### Refreshing the access token

`POST /api/v1/auth/refresh`, without a body and without `Authorization`, with the refresh cookie
(a browser sends it on its own; `fetch` needs `credentials: "include"` only from another origin)
and the header `Origin` (a browser sends it with every `POST`).

**HTTP 200** with `Cache-Control: no-store`, the same body as the login (a new access token) and a
**new** refresh cookie: the one sent is revoked (rotation).

* **HTTP 401** `INVALID_REFRESH_TOKEN`, with the cookie deleted (`Max-Age=0`) and without
  `WWW-Authenticate`, if the cookie is missing, unknown, expired or revoked, or its user is deleted
  or has logged out everywhere. The client asks for the password again. The cases are not told
  apart.
* A cookie that has already been replaced and is sent again revokes its whole family: either it was
  stolen, or two requests raced with one cookie, and the server cannot tell which. A client must
  therefore never refresh twice at once with the same cookie, not even from two tabs.
* **HTTP 403** `FORBIDDEN` if `Origin` is missing or not in `droso.auth.allowed-origins`; the cookie
  is left untouched.
* **HTTP 401** `UNAUTHORIZED` if an `Authorization` header with an invalid or expired token is sent
  along: the bearer filter rejects it before the endpoint. A client sends no access token here.
* A refresh token is valid for 7 days after its issue (`droso.auth.refresh-token-ttl`); each refresh
  issues a new one, so a user who comes back within a week stays logged in. The tokens of one login
  end 30 days after it (`droso.auth.refresh-token-max-lifetime`), however often they are refreshed;
  the last cookie expires with them.
* The cookie is `HttpOnly` (no script can read it), `Secure` (browsers accept it on
  `http://localhost` too), `SameSite=Strict` and limited to `Path=/api/v1/auth`. Only the SHA-256
  of the token is stored (`refresh_tokens`).

### Logging out

* `POST /api/v1/auth/logout` with the cookie and `Origin`, like a refresh: **HTTP 204**, the
  refresh tokens of this login are revoked and the cookie is deleted. Always `204`, with or without
  a valid cookie (`403 FORBIDDEN` without an allowed `Origin`). The access token stays valid until
  it expires (at most 15 minutes): the client forgets it.
* `POST /api/v1/auth/logout-all` with an access token (any role): **HTTP 204**, every login of the
  user on every device ends at once: `token_version` is incremented, so every access token (this one
  included) and every refresh token fails from the next request on. The cookie is deleted as well.

### The current user

`GET /api/v1/auth/me` with a token answers **HTTP 200** with the user, as currently stored (the
role may differ from the claim of the token), and the [permissions](#roles-and-permissions) of
that role, in a fixed order:

```json
{
  "id": 1,
  "email": "qwe.qwe+1@gmail.com",
  "role": "USER",
  "permissions": ["TICKET_READ", "TICKET_CREATE"],
  "createdAt": "2026-10-27T10:15:30.123456Z"
}
```

The permissions are a hint for the interface; the server checks them on every request anyway.

### Managing users

Needs the permission `USER_MANAGE` (the role `ADMIN`); any other user gets `403 FORBIDDEN`. A
deleted user is absent for every endpoint below (`404 RESOURCE_NOT_FOUND`). There is always at
least one active administrator: demoting or deleting the last one answers **HTTP 409**
`LAST_ADMIN` and changes nothing, also when two administrators demote or delete each other at the
same moment (the server serializes such changes).

**`GET /api/v1/users?page=0&size=20&sort=id&direction=desc`** answers **HTTP 200** with a page of
the active users, by default the newest first:

```json
{
  "items": [
    { "id": 1, "email": "admin@example.com", "role": "ADMIN", "createdAt": "2026-10-30T09:00:00.123456Z" }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

`page` counts from 0 (default 0), `size` is 1..100 (default 20). A value outside answers
`400 VALIDATION_ERROR` (e.g. `"fieldErrors": {"size": "must be less than or equal to 100"}`)
rather than being cut silently; a page past the end has no items.

| Parameter   | Values                     | Default |
|-------------|----------------------------|---------|
| `sort`      | `id`, `email`, `role`      | `id`    |
| `direction` | `asc`, `desc`              | `desc`  |

`direction` defaults to `desc` also when only `sort` is given. The email is ordered alphabetically
byte by byte, punctuation included (`a-c@…` < `a.b@…` < `ab@…`), on every server: the column has
the collation `C`. The role is ordered alphabetically by its code (`ADMIN` < `MANAGER` < `USER`),
not by its permissions. Users with an equal value follow by id in the same direction, so that the
pages neither repeat nor skip a user. The values are spelled in lower case only: anything else
(`Email`, `ASC`, `name`) answers `400 VALIDATION_ERROR` with
`"fieldErrors": {"sort": "has an invalid value"}` (or `direction`).

**`PUT /api/v1/users/{id}/role`** with `{"role": "MANAGER"}` answers **HTTP 200** with the user
(as in the list). The same role changes nothing. The user stays logged in: the permissions of the
new role apply from the next request, while the claim `role` of their token keeps the old value
until the next login (read the role from [`/me`](#the-current-user)). A missing role, an unknown
one or another property answers `400 VALIDATION_ERROR`.

**`DELETE /api/v1/users/{id}`** answers **HTTP 204** without a body. The user is soft-deleted
(`deleted_at`): every token is revoked at once (`token_version` is incremented), the login fails
with `401 INVALID_CREDENTIALS`, and the email may be registered again, as a new user with a new id.
A deleted user cannot be restored. An administrator may demote or delete themselves while another
active administrator exists.

An id that is not canonical answers `400`, as for the tickets.

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
| 401  | `INVALID_REFRESH_TOKEN` | A failed refresh; the refresh cookie is deleted                                      |
| 403  | `FORBIDDEN`           | A valid token that is not enough; a request from an origin that is not allowed (CORS, refresh, logout) |
| 403  | `REGISTRATION_DISABLED` | A registration while `droso.auth.registration-enabled` is `false`                    |
| 404  | `RESOURCE_NOT_FOUND`  | `ResourceNotFoundException` is thrown, or the URL is unknown                            |
| 409  | `EMAIL_ALREADY_REGISTERED` | A registration with the email of an active user; `fieldErrors.email` is set        |
| 409  | `LAST_ADMIN`          | Demoting or deleting the last active administrator                                     |
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
  `org.example.security` (`401`, `403` including a refused cross-origin request, the `400` of the firewall, and a `500 INTERNAL_ERROR` when
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
| `droso.jwt.access-token-ttl` | `15m`               | Lifetime of an access token          |
| `droso.auth.refresh-token-ttl` | `7d`              | Lifetime of a refresh token after its issue (renewed by every refresh) |
| `droso.auth.refresh-token-max-lifetime` | `30d`    | Lifetime of the refresh tokens of one login, however often refreshed |
| `droso.auth.allowed-origins` (`DROSO_AUTH_ALLOWED_ORIGINS`) | `http://localhost:4200` | The origins (`scheme://host[:port]`, comma-separated) of the pages allowed to call the API from a browser: CORS, refresh, logout |
| `DROSO_BOOTSTRAP_ADMIN_EMAIL` / `DROSO_BOOTSTRAP_ADMIN_PASSWORD` | none | The first administrator, created on start-up when there is none |

`DROSO_JWT_SECRET` has no default anywhere, on purpose: whoever knows the secret can forge a token
of any user, so it must never be in the repository. The application does not start without it, or
with a value that is not Base64 or shorter than 32 bytes, and the message says what to set.
Generate one per environment and keep it out of Git:

```bash
openssl rand -base64 32
```

Changing the secret invalidates every issued access token; the refresh tokens do not depend on it
and get new access tokens.

A deployment sets `DROSO_AUTH_ALLOWED_ORIGINS` to the origin of its frontend, e.g.
`https://droso.example.com`. An invalid entry (a path, a trailing slash, `*`) stops the start-up.

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
`401`; so does `deleted_at = now()`. `/me` lists `"permissions":["TICKET_READ","TICKET_CREATE"]`;
after `UPDATE users SET role_code = 'MANAGER'` the same token lists `TICKET_UPDATE` too.

### Refreshing and logging out

A cookie jar plays the browser; `Origin` is the one of the development server of the frontend:

```bash
curl -i -c jar -X POST http://localhost:8080/api/v1/auth/login   -H "Content-Type: application/json"   -d '{"email": "qwe.qwe+1@gmail.com", "password": "Sup3r-Secret!"}'
curl -i -b jar -c jar -X POST http://localhost:8080/api/v1/auth/refresh   -H "Origin: http://localhost:4200"
```

The login sets `droso_refresh` (`HttpOnly; Secure; SameSite=Strict`); the refresh answers `200`
with a new access token and a new cookie. Then:

* the refresh without `-H "Origin: ..."`, or with `Origin: https://evil.example.com`, answers
  `403 FORBIDDEN`;
* `cp jar old` before a refresh and `curl -b old ...` after it: `401 INVALID_REFRESH_TOKEN`, and
  the current cookie of `jar` fails as well (the family is revoked; the log warns
  `A revoked refresh token of user ... was presented again`);
* `curl -i -b jar -c jar -X POST http://localhost:8080/api/v1/auth/logout -H "Origin: http://localhost:4200"`
  answers `204` and deletes the cookie;
* `curl -i -X POST http://localhost:8080/api/v1/auth/logout-all -H "Authorization: Bearer $TOKEN"`
  answers `204`, and the same token answers `401` afterwards.

```sql
SELECT id, user_id, family_id, token_version, expires_at, family_expires_at, revoked_at, created_at FROM refresh_tokens;
```

### The first administrator

To get an administrator, start the application once with `DROSO_BOOTSTRAP_ADMIN_EMAIL=admin@example.com`
and `DROSO_BOOTSTRAP_ADMIN_PASSWORD=Adm1n-Secret!` on a database without one: the log says
`Created the administrator with the id ...`, and the login with them answers a token whose `/me`
has `"role":"ADMIN"`. Without the variables the log warns `There is no administrator`.

### Creating a ticket

```bash
curl -i -X POST http://localhost:8080/api/v1/tickets   -H "Authorization: Bearer $TOKEN"   -H "Content-Type: application/json"   -d '{"title": "Login fails", "priority": "HIGH"}'
```

Without the header it answers `401 UNAUTHORIZED` and stores nothing.

`201` with `Location: /api/v1/tickets/1` and the ticket with `"status":"NEW"`. The row is in the
database (`psql -h localhost -U <POSTGRES_USER> <POSTGRES_DB>`, see `db-local.properties`):

```sql
SELECT id, title, priority, status, created_by, created_at, updated_at FROM tickets;
```

`created_by` (and `createdBy` in the response) is the id of the user of the token.

`'{"title": "   "}'` answers `400 VALIDATION_ERROR` with `fieldErrors.title`, `'{"title": "Login
fails", "status": "CLOSED"}'` answers `400` with `"status": "is not allowed"`, `'{"title": '`
answers `400 MALFORMED_REQUEST`.

### Getting a ticket

```bash
curl -i http://localhost:8080/api/v1/tickets/1 -H "Authorization: Bearer $TOKEN"
```

`200` with the ticket created above. `/api/v1/tickets/999999` answers `404 RESOURCE_NOT_FOUND`,
`/api/v1/tickets/abc` and `/api/v1/tickets/0x1` answer `400 VALIDATION_ERROR` with
`fieldErrors.id`. The row in the database is unchanged by the GET.

On Windows, `curl` does not pass a non-ASCII `-d` argument as UTF-8, so a Cyrillic title arrives as
invalid UTF-8 and answers `400 MALFORMED_REQUEST`. Put such a body into a UTF-8 file and send it
with `--data-binary @ticket.json`.

### Managing users

With the token of an administrator (see the bootstrap above) in `$ADMIN_TOKEN`:

```bash
curl -i "http://localhost:8080/api/v1/users?size=10" -H "Authorization: Bearer $ADMIN_TOKEN"
curl -i "http://localhost:8080/api/v1/users?sort=email&direction=asc" -H "Authorization: Bearer $ADMIN_TOKEN"
curl -i -X PUT http://localhost:8080/api/v1/users/2/role -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d '{"role": "MANAGER"}'
curl -i -X DELETE http://localhost:8080/api/v1/users/2 -H "Authorization: Bearer $ADMIN_TOKEN"
```

`200` with the page (the second sorted by email), `200` with `"role":"MANAGER"`, `204`. Afterwards the token of the user 2
answers `401`, and the row has `deleted_at` set and `token_version` incremented. Demoting or
deleting the only administrator answers `409 LAST_ADMIN`; the same requests with the token of a
`USER` answer `403 FORBIDDEN`.

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
