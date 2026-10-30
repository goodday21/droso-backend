# CLAUDE.md

Guidance for Claude Code in this repository. The user-facing documentation is
[`backend/README.md`](backend/README.md); this file holds what is not obvious from the code.

## Layout

- The Gradle project is `backend/`; run every command from there. The repository root holds only
  `README.md`, `CLAUDE.md`, `.github/workflows/ci.yml` and the project skills of Claude Code in
  `.claude/skills/` (`start-task`: plan before any edit; `new-endpoint`: checklist of an endpoint;
  `test-pyramid`: mandatory checks and measurement whenever tests are added or changed).
- The Angular frontend lives in its own repository, `droso-frontend` (cloned next to this one).
- Packages under `org.example`:
  - `health` — `GET /api/v1/health` (200 UP / 503 DOWN, runs `SELECT 1`, touches no table).
  - `defect` — JPA entity `Defect` with `DefectPriority` and `DefectStatus`, `DefectRepository`,
    `DefectService`, `DefectController` (`POST /api/v1/defects` needs `DEFECT_CREATE`,
    `GET /api/v1/defects/{id}` needs `DEFECT_READ`); DTOs in `defect.model`.
  - `user` — JPA entity `User` (table `users`), `Role` (`ADMIN`, `MANAGER`, `USER`; one per user)
    with its `permissions()`, `Permission` (`DEFECT_READ`, `DEFECT_CREATE`, `DEFECT_UPDATE`,
    `USER_MANAGE`), `UserRepository` (also `isEmailTaken` for the violation of
    `users_email_active_key`), the rules `EmailAddress` and `PasswordPolicy`, their constraints
    `@ValidEmail`/`@ValidPassword` in `user.validation`; `UserResponse`, `CurrentUserResponse`
    (`/me`, with the permissions) in `user.model`. `UserController` + `UserManagementService`
    (`USER_MANAGE`): `GET /api/v1/users` (page 0.., size 1..100), `PUT /api/v1/users/{id}/role`,
    `DELETE /api/v1/users/{id}`; a deleted user is 404 everywhere; the last active admin cannot be
    demoted or deleted (`LastAdminException` → 409 `LAST_ADMIN`). Every change locks the active
    admins (`lockActiveAdmins`, ordered by id) and then the user row (`lockById`), in that order:
    a count without locks lets two admins demote each other, and without the row lock a concurrent
    role change would overwrite a deletion (Hibernate writes every column). `User.delete` bumps
    `token_version` itself.
  - `auth` — `AuthController` (`POST /api/v1/auth/register`, `POST /api/v1/auth/login`,
    `GET /api/v1/auth/me`), `RegistrationService`, `LoginService`, `AuthProperties` (`droso.auth.*`);
    DTOs in `auth.model`.
  - `bootstrap` — `BootstrapAdminService`, run on start-up by `BootstrapAdminRunner`: creates an
    `ADMIN` from `droso.bootstrap.admin.*` (`DROSO_BOOTSTRAP_ADMIN_EMAIL/PASSWORD`) when no active
    one exists; a taken email or invalid settings stop the start-up.
  - `security` — `PasswordEncoderConfig` (BCrypt cost 12 behind a `DelegatingPasswordEncoder`);
    `SecurityConfig` (the filter chain), `MethodSecurityConfig` (`@EnableMethodSecurity`),
    `JwtConfig` + `JwtProperties` (`droso.jwt.*`, HS256),
    `AccessTokenService` (issues the tokens), `UserJwtAuthenticationConverter` (loads the user per
    request; principal `AuthenticatedUser`), and the writers of `ApiError` outside Spring MVC
    (`ApiAuthenticationEntryPoint`, `ApiAccessDeniedHandler`, `ApiAuthenticationFailureHandler`,
    `ApiRequestRejectedHandler`).
  - `text` — `InvisibleText`.
  - `web` — `CanonicalIdEditor`: ids of URLs in canonical decimal form only (Spring's default
    conversion would read `0x1A`, `026`, `+26` as 26); register it with `@InitBinder` per controller.
  - `web.error` — `ApiExceptionHandler`, `ApiError`, `ErrorCode`, `ResourceNotFoundException`.
- `src/test/java/org/example/tools` holds the local database launcher (`PostgresLauncher`,
  `LocalDatabaseConfig`); it is not test code, it lives there to use the test dependencies.

## Stack and its pitfalls

Java 21, Spring Boot 4.1.1 (Spring 7), Hibernate 7, Hibernate Validator 9, Jackson 3, Flyway,
PostgreSQL. Spring Boot 4 differs from 3 in ways that are easy to get wrong:

- Auto-configuration is split into modules: Flyway needs `spring-boot-flyway`, MockMvc/`@WebMvcTest`
  need `spring-boot-webmvc-test` (`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`).
- Jackson 3 lives in `tools.jackson.*`; only the annotations stay in `com.fasterxml.jackson.annotation`.
- The locale properties are `spring.web.locale` / `spring.web.locale-resolver`. The old
  `spring.mvc.locale*` are deprecated with level `error` and silently not bound.
- Before relying on a property or an API, check it in the jar (`~/.gradle/caches/modules-2`,
  `META-INF/spring-configuration-metadata.json` or the `-sources.jar`), not from memory.

## Database and schema

- The schema is owned by Flyway (`src/main/resources/db/migration`); Hibernate only validates it
  (`spring.jpa.hibernate.ddl-auto: validate`). An entity change needs a new migration `V<n>__*.sql`;
  never edit a migration that is already on `main`.
- Enums are stored as `VARCHAR` by name with a `CHECK` constraint listing the values: a new enum
  value needs a migration replacing the constraint. Timestamps are `TIMESTAMPTZ` mapped to `Instant`.
- `Defect`: the title is stripped and must be 5..120 code points (`IllegalArgumentException`
  otherwise); an optional text that is invisible as a whole is stored as `null`, any other is kept
  as given; priority defaults to `MEDIUM`; status is always `NEW`; `@PrePersist` sets
  `createdAt == updatedAt`, truncated to microseconds. No setters, no `@PreUpdate` yet.
- "Stripped" and "blank" mean `InvisibleText`, never `String.strip()`/`isBlank()` alone: those miss
  the no-break spaces (U+00A0, U+2007, U+202F) and the zero-width characters. The DTO normalizes
  the same way before validation, so that the rules apply to the stored values.
- `users.token_version` (claim `ver`): incrementing it revokes every access token of the user; do
  it on every change that must log the user out (deletion, password, role if needed).
- `User`: the email is the login, stored stripped (`InvisibleText`) and lower-cased with
  `Locale.ROOT` (the column has a `CHECK` for it); the part after `+` is kept. The password is never
  normalized and never stored, logged or returned: only `password_hash` (`{bcrypt}$2a$12$...`), and
  a DTO that holds a password overrides `toString()`. Soft delete is `deleted_at`; the email is
  unique among the active users only (partial index `users_email_active_key`, whose violation the
  registration maps to 409 — no "exists" query before the insert). Roles are rows of `roles`
  referenced by `users.role_code`: a new role needs a migration that inserts it, and `Role` must
  list the same codes. `@PreUpdate` refreshes `updated_at`.
- The local database of `./gradlew runPostgres` lives in `backend/.postgres/data` (ignored by Git).
  It used to be `build/postgres-data`: if that directory still exists, `./gradlew clean` deletes a
  developer's database. For a full rebuild prefer `./gradlew build --rerun-tasks`.

## Error responses

Every API error is an `ApiError`: `{status, code, message, fieldErrors}`. `fieldErrors` is always
present (`{}` when empty), sorted, several errors of one field joined with `; `. Codes:
`VALIDATION_ERROR` (400), `MALFORMED_REQUEST` (400), `REGISTRATION_DISABLED` (403),
`RESOURCE_NOT_FOUND` (404), `EMAIL_ALREADY_REGISTERED` (409, with `fieldErrors.email`),
`LAST_ADMIN` (409),
`INVALID_CREDENTIALS` (401, failed login), `UNAUTHORIZED` (401, with `WWW-Authenticate: Bearer`),
`FORBIDDEN` (403), `BAD_REQUEST` (400, a URL the firewall of Spring Security rejects, e.g. `;`),
`INTERNAL_ERROR` (500), otherwise the name of the HTTP status (`METHOD_NOT_ALLOWED`, …).
Messages are fixed per code: never put the text of an exception into a response. Unknown JSON
properties and enum indexes are rejected (`spring.jackson.*` in `application.yml`); a Jackson error
with a property path is a `VALIDATION_ERROR` of that property, other unreadable bodies are
`MALFORMED_REQUEST`. Text lengths are validated with `@CodePointLength` (as `VARCHAR` counts them),
not `@Size`. Throw `ResourceNotFoundException` for a missing resource. The health 200/503 bodies are a separate
contract (followed by `droso-frontend`) and must not change.

## Security

- Every request needs `Authorization: Bearer <JWT>` except those listed in `SecurityConfig`
  (`/api/v1/health`, `POST /api/v1/auth/register|login`, `/error`, `/actuator/health`); the rest of
  Actuator is `denyAll`. A new public endpoint must be added there explicitly, and to `PUBLIC` of
  `EndpointAccessIntegrationTest`, which checks every mapped endpoint without a token (401 unless
  listed there).
- `DROSO_JWT_SECRET` (Base64, >= 32 bytes) is required; there is no default anywhere, and the
  application refuses to start without it. Tests get `TestUsers.JWT_SECRET` from
  `PostgresIntegrationTest`.
- The filters run before Spring MVC: their errors never reach `ApiExceptionHandler`, they are
  written by the handlers of `security` with `ApiErrorWriter`. An exception thrown while a token is
  checked must be an `AuthenticationException`, or it escapes to Spring Boot's `/error`.
- The claims `role`/`email` of a token are for the client only; the server reads the user from the
  database on every request.
- Authorities are the `Permission`s of the current role (`Role.permissions()`), never `ROLE_*`. An
  endpoint requires one with `@PreAuthorize("hasAuthority('<PERMISSION>')")`; a new role or a change
  of the matrix happens in `Role` only. `EndpointAccessIntegrationTest` fails for a protected
  endpoint without such a check, unless it is in its `ANY_USER` (open to every user, like `/me`).
  A denied `@PreAuthorize` throws inside Spring MVC and is answered by `ApiExceptionHandler`
  (`403 FORBIDDEN`), after the body has been validated.

## Tests

- `./gradlew test` (CI runs exactly this); `./gradlew build --rerun-tasks` for a full local check.
- Adding or changing tests: follow the skill `test-pyramid` (lowest possible layer, no new Spring
  context or isolated server without a reason, `pyramid.sh --run` before and after in the report).
- Spring tests that need a database extend `org.example.support.PostgresIntegrationTest`: one
  zonky `EmbeddedPostgres` (PostgreSQL 14, free port, `lc_messages=C`, no Docker) shared by all
  test classes of the JVM, wired with `@DynamicPropertySource`, so that Spring's context cache
  reuses one application per configuration (keep the configurations few: every distinct
  `@SpringBootTest(properties)`, web environment or `@MockitoBean` starts another context). Before
  every test, all tables except `flyway_schema_history` and `roles` are truncated (`RESTART
  IDENTITY`): do not rely on data of another test, and do not delete tables by hand. It also clears
  `droso.bootstrap.admin.*`, so no administrator is created on start-up.
- Such a test must never stop the server. A test that does (e.g. 503/500 while PostgreSQL is down)
  extends `IsolatedPostgresIntegrationTest`: a server and a context per class, with
  `@DirtiesContext(AFTER_CLASS)`, which must stay there (the cached context would point to the
  stopped server). Keep these classes few, each costs a server and a context (~3 s). A test
  without Spring calls `PostgresIntegrationTest.startServer()`.
- A test that calls a protected endpoint stores a user and sends a real token:
  `TestUsers.create(users, Role.USER)` + `TestUsers.bearer(tokens, user)` (MockMvc:
  `.with(TestUsers.authorization(bearer))`), not `jwt()` of spring-security-test, which skips the
  decoder and the converter.
- Web tests without a database use `@WebMvcTest`, with `@AutoConfigureMockMvc(addFilters = false)`:
  the slice would apply Spring Boot's default security, not `SecurityConfig`. Do not make a
  `Converter` a `@Component` in `security`: the slices pick up every `Converter` bean.
- A controller that exists only for a test is a `static` class nested in the test class and added
  with `@Import`: nested in a test class, it is ignored by the component scan of the other Spring
  Boot tests.
- The build forces English (`user.language=en`, `LC_ALL=C`) because the host is Russian; keep new
  launch configurations consistent with that.

## Conventions

- Code, comments, commit messages and PR descriptions in English, in the style of the existing ones
  (explanatory Javadoc and comments that say *why*).
- One branch per task (e.g. `task-003-error-handling`), a PR into `main`, squash merge, branch deleted.
  PR descriptions end with a "Known limitations" section and state only checks that were really run.
