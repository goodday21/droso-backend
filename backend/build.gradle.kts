plugins {
    id("java")
    id("org.springframework.boot") version "4.1.1"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val springBootVersion = "4.1.1"

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // JPA mapping of the domain entities. The schema itself is owned by Flyway.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Bean Validation (Hibernate Validator) of the request bodies and parameters.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // The password encoders (BCrypt) only. The rest of Spring Security comes with the login: until
    // then it would answer every request of the API with 401.
    implementation("org.springframework.security:spring-security-crypto")

    // Spring Boot 4 split the auto-configuration of Flyway into a dedicated module.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 moved the MockMvc auto-configuration into a dedicated module.
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    // Real PostgreSQL 14 started in-process: no Docker daemon required.
    testImplementation("io.zonky.test:embedded-postgres:2.2.2") {
        exclude(group = "org.flywaydb", module = "flyway-core")
        exclude(group = "org.liquibase", module = "liquibase-core")
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Spring Boot 4.x supports Java 17..26 only; the project targets Java 21 LTS.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.test {
    useJUnitPlatform()
    // The JDBC driver localizes its own messages with the locale of the JVM, so a Russian host
    // would produce a Russian "Position: 15" inside an otherwise English server message.
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    // The PostgreSQL utilities (pg_ctl, initdb) translate their output with gettext, which reads
    // the locale from the environment. "C" keeps their output in English as well.
    environment("LC_ALL", "C")
    environment("LANG", "C")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.bootRun {
    // Same reason as in the test task: keep the messages of the JDBC driver in English.
    jvmArgs("-Duser.language=en", "-Duser.country=US")
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    environment("LC_ALL", "C")
    environment("LANG", "C")
}

/**
 * Starts a real PostgreSQL server on port 5432 without Docker, so that the application can be
 * started locally: `./gradlew runPostgres` in one terminal, `./gradlew bootRun` in another.
 *
 * <p>The launcher owns exactly one data directory (`.postgres/data` by default, outside of
 * `build/`, so that `./gradlew clean` does not wipe the local database). If the port
 * already serves a PostgreSQL instance whose data directory is that one, the running server is
 * reused; if it serves a foreign instance, the launcher fails with an explicit message instead of
 * touching that server.
 */
tasks.register<JavaExec>("runPostgres") {
    group = "application"
    description = "Runs a local PostgreSQL server (no Docker required)."
    mainClass = "org.example.tools.PostgresLauncher"
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    environment("LC_ALL", "C")
    environment("LANG", "C")
    // The port and the data directory are configurable. Gradle project properties ("-P") and
    // system properties ("-D") are both accepted; the environment variables POSTGRES_PORT and
    // POSTGRES_DATA_DIR are inherited by the forked JVM anyway.
    listOf("postgres.port", "postgres.dataDir").forEach { name ->
        val value = providers.gradleProperty(name)
            .orElse(providers.systemProperty(name))
            .orNull
        if (!value.isNullOrBlank()) {
            systemProperty(name, value)
        }
    }
}
// Hidden helper of bootRunWithPostgres: runPostgres in the detached mode, i.e. it returns as soon as
// the server is ready (or reused) and leaves it running, so that the build can go on to the
// application. Its settings are taken from runPostgres, so that the two cannot drift apart.
val startPostgres = tasks.register<JavaExec>("startPostgres") {
    description = "Starts the local PostgreSQL server and returns once it is ready."
    val runPostgres = tasks.named<JavaExec>("runPostgres").get()
    mainClass = runPostgres.mainClass
    classpath = runPostgres.classpath
    systemProperties(runPostgres.systemProperties)
    environment(runPostgres.environment)
    systemProperty("postgres.detach", "true")
}

/**
 * Starts the local PostgreSQL server and then the application with one command:
 * `./gradlew bootRunWithPostgres`. The application is configured exactly as by bootRun.
 *
 * <p>The server keeps running after the application has been stopped, and the next launch reuses
 * it, just like after `./gradlew runPostgres`.
 */
tasks.register<org.springframework.boot.gradle.tasks.run.BootRun>("bootRunWithPostgres") {
    group = "application"
    description = "Runs the local PostgreSQL server and then the application."
    dependsOn(startPostgres)
    val bootRun = tasks.bootRun.get()
    mainClass = bootRun.mainClass
    classpath = bootRun.classpath
    jvmArgs(bootRun.jvmArgs)
    systemProperties(bootRun.systemProperties)
    environment(bootRun.environment)
}
