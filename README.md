# droso-backend

A Spring Boot service with a readiness endpoint that verifies the real availability of PostgreSQL.
The code, its build and its documentation live in [`backend/`](backend/README.md).

The Angular page that shows the status lives in its own repository,
[droso-frontend](https://github.com/goodday21/droso-frontend).

## Quick start

```bash
cd backend
cp src/main/resources/db-local.example.properties src/main/resources/db-local.properties   # once
./gradlew bootRunWithPostgres
```

The service answers on <http://localhost:8080/api/v1/health>. See
[`backend/README.md`](backend/README.md) for everything else.

## Local development with the frontend

Clone both repositories next to each other, then follow the
[walkthrough of droso-frontend](https://github.com/goodday21/droso-frontend#manual-walkthrough):

```
IdeaProjects/
├── droso-backend/
└── droso-frontend/
```

## IntelliJ IDEA

Open the repository root. The Gradle project is linked from `backend/` (`.idea/gradle.xml`); if the
IDE still shows the old layout, reload it from the Gradle tool window.
