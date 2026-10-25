# droso-backend

A Spring Boot service with a readiness endpoint that verifies the real availability of PostgreSQL,
and an Angular page that shows its status.

| Directory                         | Contents                                                  |
|-----------------------------------|-----------------------------------------------------------|
| [`backend/`](backend/README.md)   | Spring Boot service, Gradle build, local PostgreSQL tools |
| [`frontend/`](frontend/README.md) | Angular application                                       |

Each directory is self-contained: it has its own build, README and `.gitignore`, and its commands
are run from inside it.

## Quick start

```bash
# PostgreSQL and the backend on :8080
cd backend
cp src/main/resources/db-local.example.properties src/main/resources/db-local.properties   # once
./gradlew bootRunWithPostgres

# the frontend on :4200, in a second terminal
cd frontend
npm install                                                                                # once
npm start
```

Open <http://localhost:4200/>. See [`backend/README.md`](backend/README.md) and
[`frontend/README.md`](frontend/README.md) for everything else.

## IntelliJ IDEA

Open the repository root. The Gradle project is linked from `backend/` (`.idea/gradle.xml`); if the
IDE still shows the old layout, reload it from the Gradle tool window.
