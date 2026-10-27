# CLAUDE.md

Guidance for Claude Code in this repository. The user-facing documentation is
[`backend/README.md`](backend/README.md); this file holds what is not obvious from the code.

## Layout

- The Gradle project is `backend/`; run every command from there. The repository root holds only
  `README.md`, `CLAUDE.md` and `.github/workflows/ci.yml`.
- The Angular frontend lives in its own repository, `droso-frontend` (cloned next to this one).
- Packages under `org.example`:
  - `health` — `GET /api/v1/health` (200 UP / 503 DOWN, runs `SELECT 1`, touches no table).
  - `defect` — JPA entity `Defect` with `DefectPriority` and `DefectStatus`, `DefectRepository`,
    `DefectService`, `DefectController` (`POST /api/v1/defects`, `GET /api/v1/defects/{id}`);
    DTOs in `defect.model`.
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
- The local database of `./gradlew runPostgres` lives in `backend/.postgres/data` (ignored by Git).
  It used to be `build/postgres-data`: if that directory still exists, `./gradlew clean` deletes a
  developer's database. For a full rebuild prefer `./gradlew build --rerun-tasks`.

## Error responses

Every API error is an `ApiError`: `{status, code, message, fieldErrors}`. `fieldErrors` is always
present (`{}` when empty), sorted, several errors of one field joined with `; `. Codes:
`VALIDATION_ERROR` (400), `MALFORMED_REQUEST` (400), `RESOURCE_NOT_FOUND` (404),
`INTERNAL_ERROR` (500), otherwise the name of the HTTP status (`METHOD_NOT_ALLOWED`, …).
Messages are fixed per code: never put the text of an exception into a response. Unknown JSON
properties and enum indexes are rejected (`spring.jackson.*` in `application.yml`); a Jackson error
with a property path is a `VALIDATION_ERROR` of that property, other unreadable bodies are
`MALFORMED_REQUEST`. Text lengths are validated with `@CodePointLength` (as `VARCHAR` counts them),
not `@Size`. Throw `ResourceNotFoundException` for a missing resource. The health 200/503 bodies are a separate
contract (followed by `droso-frontend`) and must not change.

## Tests

- `./gradlew test` (CI runs exactly this); `./gradlew build --rerun-tasks` for a full local check.
- Tests that need a database start their own PostgreSQL 14 with zonky `EmbeddedPostgres`
  (`setPort(0)`, `setServerConfig("lc_messages", "C")`, `@DynamicPropertySource`); no Docker.
  Copy the setup of `DefectPersistenceIntegrationTest` or `FlywayMigrationIntegrationTest`.
- Web tests without a database use `@WebMvcTest`. A controller that exists only for a test is a
  `static` class nested in the test class and added with `@Import`: nested in a test class, it is
  ignored by the component scan of the other Spring Boot tests.
- The build forces English (`user.language=en`, `LC_ALL=C`) because the host is Russian; keep new
  launch configurations consistent with that.

## Conventions

- Code, comments, commit messages and PR descriptions in English, in the style of the existing ones
  (explanatory Javadoc and comments that say *why*).
- One branch per task (e.g. `task-003-error-handling`), a PR into `main`, squash merge, branch deleted.
  PR descriptions end with a "Known limitations" section and state only checks that were really run.
