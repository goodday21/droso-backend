---
name: new-endpoint
description: Checklist for adding or changing a REST endpoint of droso-backend (controller, DTO, service, errors, security, tests, README) so that it follows the contracts of the existing API. Use when planning or implementing a new endpoint under /api/v1 or changing the request, response or access rules of an existing one.
---

# Adding a REST endpoint

The existing endpoints are the reference: `DefectController` (create + get by id) and
`AuthController` (public endpoints, `/me` with the principal). Copy their shape, not just the idea.
Each item below exists because it is easy to miss and the tests or the contract depend on it.

## Contract first

Write down, before the code (and in the plan of `start-task`):

- method, path under `/api/v1/<resource>`, `consumes = MediaType.APPLICATION_JSON_VALUE` for a body;
- the success status (`201` + relative `Location` for a create, `200` otherwise, `204` for no body)
  and the response body;
- every error status with its `ErrorCode` and, for `400 VALIDATION_ERROR`, the `fieldErrors` keys;
- who may call it: public, any user, or a permission (see Security below).

## Controller

- `@RestController` + `@RequestMapping(<Name>Controller.PATH)`, `static final String PATH`.
- A path variable id → `@InitBinder` registering `CanonicalIdEditor` for `Long` in this controller
  (it is per controller): otherwise `0x1A`, `026`, `+26` are read as 26.
- Javadoc on the class listing the error answers, on each method its statuses: the existing
  controllers do it and the README is written from it.
- The current user comes from `@AuthenticationPrincipal AuthenticatedUser`, which is loaded from
  the database per request; never from the claims `role`/`email` of the token.
- A response that carries a token or a secret: `Cache-Control: no-store` (see `login`).

## Request DTO (`<package>.model`)

- A `record`; unknown JSON properties are already rejected, so do not add fields the client must
  not set (id, status, timestamps, owner) - a request with them answers `400`.
- Text: normalize in the compact constructor with `InvisibleText` (`strip`, `blankToNull`), never
  `String.strip()`/`isBlank()` alone, so that the validation sees the value that will be stored.
- Lengths: `@CodePointLength` with the constants of the entity, not `@Size`.
- Email/password: `@ValidEmail`/`@ValidPassword`. A DTO with a password overrides `toString()` and
  the password is never logged or returned.
- Enums are bound by name; an index (`1`) is rejected by the configuration of Jackson.

## Service and entity

- Rules that must hold for every caller live in the entity/service, not only in the DTO
  (e.g. `Defect` strips and checks the title again).
- A missing resource: `throw new ResourceNotFoundException("<Resource>", id)` → `404`.
- A unique conflict: let the database constraint decide and map its violation (as the registration
  does with `users_email_active_key`), no "exists" query before the insert.
- A change of the schema needs a new migration `V<n>__*.sql` (Hibernate only validates); enum
  values are a `CHECK` constraint, timestamps `TIMESTAMPTZ` ↔ `Instant`.
- A change that must log the user out (password, deletion, role if needed) increments
  `users.token_version`.

## Errors

- Only `ApiError` with a fixed message per code; never the text of an exception in a response.
- A new error meaning gets a new `ErrorCode` constant (status + fixed message), a dedicated
  exception and an `@ExceptionHandler` in `ApiExceptionHandler` that logs at `debug`. Add the code
  to the list in `CLAUDE.md` and to the README section "Error responses".
- Errors raised in the security filters never reach `ApiExceptionHandler`: they go through the
  handlers of `security` and `ApiErrorWriter`; an exception thrown while a token is checked must be
  an `AuthenticationException`.

## Security

- Every request is authenticated by default (`anyRequest().authenticated()`). A public endpoint
  must be added explicitly in `SecurityConfig`, with the method, and with a comment saying why it is
  public; do the same in the table of the README section "Authentication".
- Without a token: `401 UNAUTHORIZED` + `WWW-Authenticate: Bearer`; with a token that is not
  enough: `403 FORBIDDEN`. Both come from the existing handlers - do not answer them from the
  controller.
- Once permissions exist (TASK-008): `@PreAuthorize("hasAuthority('<PERMISSION>')")` on
  permissions, never on roles.

## Tests

- An integration test class `<Resource><Action>IntegrationTest extends PostgresIntegrationTest`
  with `@SpringBootTest` + `@AutoConfigureMockMvc`
  (`org.springframework.boot.webmvc.test.autoconfigure`). Do not remove its inherited
  `@DirtiesContext`.
- A real token: `TestUsers.create(users, Role.USER)` + `TestUsers.bearer(tokens, user)`, sent with
  `.with(TestUsers.authorization(bearer))`. Not `jwt()` of spring-security-test.
- Compare error bodies as whole JSON (`jsonMapper.readTree(...)`), as the existing tests do, so
  that a changed code, message or `fieldErrors` fails.
- Cover at least: success (status, headers, body, the stored row), `401` without a token (and with
  a broken one), `403` if a permission is required, every `400` of the DTO (missing, too long,
  invisible, unknown property, wrong enum), `404` for a missing id, non-canonical ids
  (`026`, `0x1A`, `+26`), any `409`.
- A web test without a database: `@WebMvcTest` + `@AutoConfigureMockMvc(addFilters = false)`.
  A controller needed only by a test: a `static` class nested in the test class, added with
  `@Import`.
- Before the full run: `./gradlew test --tests 'org.example.<package>.*'`; then `./gradlew test`.

## Documentation

- `backend/README.md`: a section under "API contract" (request, response, errors) and a `curl`
  example under "Manual verification" with `-H "Authorization: Bearer $TOKEN"`.
- `CLAUDE.md`: the endpoint in the list of its package; a new error code; a new public path.
- The health 200/503 bodies are a separate contract of `droso-frontend`: never change them.
