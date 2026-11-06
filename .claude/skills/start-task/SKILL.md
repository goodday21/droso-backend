---
name: start-task
description: Start a Droso task (TASK-00N) the way the maintainer expects - read-only exploration, a file-level plan with risks and the decisions that are the user's to make, and no edit before the plan is approved. Use when the user hands over a TASK-00N spec or says "начинай TASK-00N" (or "start TASK-00N").
---

# Starting a task

A task arrives as a "TASK-00N" spec, usually in Russian. The user reviews a plan before any change,
so the first answer to a task is a plan, never a diff.

## 1. Check the starting point

- `git status` and `git branch --show-current` from `backend/`. A task starts from an up-to-date,
  clean `main`; if the tree is dirty or another branch is checked out, say so and ask, do not stash
  or switch on your own.
- `git log --oneline -5`: the previous task must already be merged (squash) into `main`.

## 2. Gather what was already decided

- The spec itself: list every requirement it states, so that the plan can be checked against it.
- The memory: the agreed plan of the task (e.g. `auth-plan` for TASK-007..014) and the open
  decisions. They were agreed with the user: follow them, and if the code has moved on and a
  decision no longer fits, raise it as a question instead of silently deviating.
- `CLAUDE.md`: the rules of the packages the task touches (errors, security, schema, tests).

## 3. Explore, read-only

- Read the code the task touches and the closest existing example (for an endpoint:
  `TicketController`, `AuthController`, their DTOs, services and tests).
- Before relying on a property or an API of Spring Boot 4 / Spring 7 / Jackson 3 / Hibernate 7,
  check it in the jar under `~/.gradle/caches/modules-2` (`spring-configuration-metadata.json`,
  `-sources.jar`), not from memory.
- No `Edit`/`Write` on project files, no branch, no migration in this phase.

## 4. Write the plan (in Russian)

Keep the identifiers, paths and code in English. Sections, in this order:

1. **Цель** — one or two sentences, in the user's terms.
2. **Файлы** — every new and changed file with one line on what changes in it, grouped by package
   (main, migration, tests, README/`CLAUDE.md`).
3. **Контракт API** (if the task has endpoints) — method and path, request and response bodies,
   every status with its `ErrorCode`, who may call it. Use the `new-endpoint` skill for the checklist.
4. **Схема** (if any) — the next migration `V<n>__*.sql`, what it does to existing rows.
5. **Тесты** — what each new test class pins, including the failure cases (401, 403, 400, 404, 409),
   and its layer (unit / slice / db / api / http) by the checks of the `test-pyramid` skill; name
   any new Spring context configuration or `IsolatedPostgresIntegrationTest` class with its reason.
6. **Риски** — what may break: existing tests, the contract of `droso-frontend`, data of existing
   databases, behaviour of Spring Boot 4 that differs from 3.
7. **Решения за тобой** — each as a numbered question with the options, a recommendation and its
   concrete impact ("на что это влияет": for the client, the data, the security, later tasks).
   Only real choices: a default that follows from the spec, `CLAUDE.md` or the agreed plan is stated
   in the plan, not asked.
8. **Вне задачи / Known limitations** — what is deliberately left out and to which task it belongs.

Then stop and wait. Do not start "the uncontroversial part" before the approval.

## 5. After the approval

- Create the branch from `main`: `task-00n-<topic>` (e.g. `task-008-defect-permissions`).
- Work in small stages; after each, run the tests of the touched package
  (`./gradlew test --tests 'org.example.<package>.*'`), at the end `./gradlew test`, and
  `./gradlew build --rerun-tasks` for a full check. Never `./gradlew clean`.
- If the task touched `src/test`, follow the `test-pyramid` skill: its checks for every new test,
  `pyramid.sh --run` on `main` before and on the branch after, and its thresholds for asking.
- If something in the plan turns out wrong while implementing, stop and tell the user what and why
  before choosing another way.
- Commit, push and open the PR only when the user asks.
- The final report (in Russian) lists the changed files, the commands actually run with their
  results, the test pyramid before and after (if tests changed), and the known limitations. A
  check that was not run (a real PostgreSQL, CI, the frontend)
  is reported as not run, never as passed.
