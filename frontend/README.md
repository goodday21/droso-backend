# Frontend

Angular 22 application that shows the health of the service and of its PostgreSQL dependency.

All commands below are relative to this `frontend/` directory. It talks to the backend in
[`../backend`](../backend/README.md) through a single endpoint, `GET /api/v1/health`; the
[API contract](../backend/README.md#api-contract) is described there.

## Running

```bash
npm install
npm start                  # dev server on http://localhost:4200, proxies /api to :8080
npm test                   # unit tests (ChromeHeadless)
npm run build
```

The dev server forwards every `/api` request to the backend on `http://localhost:8080`
(`proxy.conf.json`), so the backend has to run as well — see [Manual walkthrough](#manual-walkthrough).

Only the root path is a page: any other path shows *Page not found*.

`HealthApiService` maps the HTTP status to three explicit outcomes:

* `up` — HTTP 200, the database is reachable;
* `down` — HTTP 503, the database is not reachable (a normal answer, not a transport error);
* `unreachable` — the backend itself could not be reached (network error).

## Tests

```bash
npm install                  # once
npm test                     # single run, ChromeHeadless
npm test -- --watch          # watch mode: re-runs on every file change
```

In watch mode Karma serves a UI at <http://localhost:9876/> — open it, tick the spec files you
want to see, and use the "Debug" button to inspect the DOM of a failing expectation.

To run a single spec, narrow the pattern, e.g. `ng test --watch --include='**/health-api.service.spec.ts'`.

## Manual walkthrough

Two terminals:

```bash
# 1) a real PostgreSQL on :5432 (no Docker needed) and the backend on :8080
cd backend && ./gradlew bootRunWithPostgres

# 2) the dev server on :4200
cd frontend && npm start
```

`./gradlew runPostgres` and `./gradlew bootRun` in two separate terminals (both in `backend/`) do
the same as step 1.

Open <http://localhost:4200/>. The panel shows a green **UP** badge with the database latency.

Now break the database and press **Refresh** in the panel:

```bash
# stops PostgreSQL — the panel must turn red with HTTP status 503
taskkill /IM postgres.exe /F
```

Restart it with `./gradlew runPostgres` in `backend/` in another terminal and press **Refresh**
again — the panel must return to green.
This proves the UI reacts to a genuinely broken database rather than to a static mock.

To see the third state, stop the backend (`Ctrl+C` in terminal 1) and press **Refresh**: the panel
shows *not reachable* instead of *DOWN*, because the service itself no longer answers.

## Notes for Windows

* In PowerShell, `npm` resolves to `npm.ps1`, which the default execution policy blocks. Use
  `npm.cmd` instead (`npm.cmd test`), or run the commands in `cmd.exe`.
