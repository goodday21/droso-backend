---
name: test-pyramid
description: Mandatory checks of the test pyramid of droso-backend whenever tests are added, moved or changed - put every case on the lowest layer that can check it, keep the Spring contexts and the isolated PostgreSQL servers few, and report the pyramid before and after with measured numbers. Use when planning or writing tests (a new endpoint, a new rule, a bug fix with a test), and before reporting a task that touched src/test.
---

# Keeping the test pyramid

The pyramid was rebuilt in PRs #22–#25 (October 2026): about half of the tests are unit tests,
and the whole suite runs in about 40 s. A new test must keep it that way: every case on the lowest
layer that can check it, and no new fixed cost (a Spring context, a PostgreSQL server) without a
reason that the user has seen.

## Layers and what belongs where

| Layer | How | What it checks here |
|---|---|---|
| unit | plain JUnit, `Validator` without Spring, Mockito | a rule or a normalization: `EmailAddress`, `PasswordPolicy`, `InvisibleText`, the bounds of an entity, a DTO and its constraints, a service with mocks |
| slice | `@WebMvcTest` + `@AutoConfigureMockMvc(addFilters = false)`, `ApplicationContextRunner`, standalone MockMvc | the format of `ApiError`, the binding of properties, a controller without the database |
| db | `PostgresIntegrationTest` without MockMvc, `startServer()` | the schema: migrations, constraints, indexes, what Hibernate maps |
| api | `PostgresIntegrationTest` + `@SpringBootTest` + MockMvc | the contract of an endpoint: one case per kind of error (status, code, `fieldErrors`, nothing stored), security, what is stored |
| http | `@SpringBootTest(webEnvironment = RANDOM_PORT)` | only what MockMvc cannot see: Tomcat, the firewall, URL decoding, a real connection |

A matrix of values (bounds, invisible characters, malformed inputs) belongs in a unit test. The
api test keeps one representative value per kind of error and checks how the violation reaches
the client.

## Checks for every new or changed test

Answer each of these while planning (the plan of `start-task` names the layer of each new test
class) and again before the report:

1. **The lowest layer.** Could this case run without Spring or without the database? Then it is a
   unit or slice test. A rule tested only through HTTP fails far from its cause.
2. **No duplicate matrix.** Is the same set of values already in a unit test (`EmailAddressTest`,
   `PasswordPolicyTest`, `CreateTicketRequestTest`, `TicketTest`, …)? Then the api test takes one of
   them, not all. Checking a rule on two layers is right only when two layers apply it on their own
   (the DTO and the entity both normalize the title).
3. **No new context.** Every distinct `@SpringBootTest(properties = …)`, web environment or
   `@MockitoBean` set starts another Spring context (~1–3 s). Reuse an existing configuration; a
   new one needs a reason in the plan.
4. **The shared server stays up.** A test that stops PostgreSQL goes into a separate class extending
   `IsolatedPostgresIntegrationTest` (a server and a context of its own, ~3 s): keep such classes
   few and their tests minimal.
5. **No hand-written cleanup.** The tables are truncated before every test; no `DELETE FROM` in
   `@BeforeEach`, no reliance on data of another test.
6. **Readable data.** Invisible characters as escapes (`\u00A0`, `\u200B`), never as literals. Note
   that the Write/Edit tools turn `\u200B` into the character itself: check the file afterwards
   (e.g. with `grep -n 'u200B'`) and fix it with a script that builds the backslash, e.g. `chr(92)`.
7. **The test can fail.** For a new kind of check, break the code it guards once (then revert) and
   see the test fail; report it.

## Measuring

From `backend/`, before the change (on `main`) and after it:

```
bash ../.claude/skills/test-pyramid/pyramid.sh --run
```

It runs `./gradlew test --rerun-tasks` and prints tests and time per layer, the share of the top
(api + http), the number of Spring context configurations and the isolated classes. Without
`--run` it reads the results of the last run (and warns if sources are newer). The layer is
guessed from the source; if a class lands on a surprising layer, say so instead of trusting it.

Put both tables (or the difference) into the final report and the PR description, under the
checks that were really run.

## When to stop and ask the user

Do not silently accept, and do not silently rework, when the change would:

- make the top (api + http) exceed **40 %** of the tests;
- add an `IsolatedPostgresIntegrationTest` class or a new Spring context configuration that the
  approved plan did not mention;
- make `./gradlew test --rerun-tasks` more than **10 %** slower than on `main` (measure twice,
  the times vary by a second or two).

State the numbers and the options (move cases down, merge configurations, accept with a reason)
and let the user decide.
