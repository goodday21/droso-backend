package org.example.tools;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Manual helper: starts a real PostgreSQL instance and prints its JDBC URL, so that the
 * application can be started manually against it.
 *
 * <p>Run with:
 * {@code ./gradlew runPostgres}
 *
 * <p>The launcher owns exactly one data directory ({@code build/postgres-data} by default). That
 * directory is what identifies the server: if the port already serves a PostgreSQL instance whose
 * data directory is the one of this launcher, that server is reused; if the port serves a foreign
 * instance, the launcher fails with an explicit message instead of silently attaching to it.
 */
public final class PostgresLauncher {

    /**
     * The superuser of the embedded server ({@code initdb -A trust -U postgres}). It is a property
     * of the embedded server itself, not a credential of the application database, so it does not
     * belong to {@code db-local.properties}.
     */
    private static final String SUPERUSER = "postgres";

    /** Port of the embedded server. Overridable for parallel setups. */
    private static final String PORT_PROPERTY = "postgres.port";

    /** Data directory owned by this launcher. Overridable. */
    private static final String DATA_DIR_PROPERTY = "postgres.dataDir";
    private static final String DATA_DIR_ENV = "POSTGRES_DATA_DIR";
    private static final String DEFAULT_DATA_DIR = "build/postgres-data";

    /**
     * Detached mode: return as soon as the server is ready and leave it running, instead of
     * blocking. Used by {@code ./gradlew bootRunWithPostgres}, which starts the application next.
     */
    private static final String DETACH_PROPERTY = "postgres.detach";

    private PostgresLauncher() {
    }


    public static void main(String[] args) throws Exception {
        // The database of the application is not declared here: the launcher reads it from the same
        // file as the application (classpath:db-local.properties), so the two cannot drift apart.
        LocalDatabaseConfig config = LocalDatabaseConfig.load();

        if (!config.isLocalHost()) {
            throw new IllegalStateException("The local launcher can only start a server on this "
                    + "machine, but the configuration points to '" + config.host() + "'. Either start "
                    + "the database there yourself, or set POSTGRES_HOST=localhost.");
        }

        int port = resolvePort(config.port());
        Path dataDir = resolveDataDir();
        Files.createDirectories(dataDir);
        boolean detach = Boolean.getBoolean(DETACH_PROPERTY);

        String jdbcUrl = "jdbc:postgresql://localhost:" + port;

        // The lock serialises the launchers that share the same data directory. Without it, two
        // launchers started at the same time would run "initdb" on the same directory at once and
        // both would fail. The lock is released as soon as the server is up and the application
        // database is prepared: the running server then serves every later launcher.
        try (LauncherLock ignored = LauncherLock.acquire(lockFileFor(dataDir))) {
            String ownerDataDir = probeDataDirectory(jdbcUrl);

            if (ownerDataDir != null) {
                // Something already serves the port: either our own server from an earlier run, or a
                // foreign instance that must not be touched.
                if (!isSameDirectory(ownerDataDir, dataDir)) {
                    throw new IllegalStateException(foreignServerMessage(port, ownerDataDir));
                }
                System.out.println(reuseMessage(port, ownerDataDir));
            } else {
                if (isPortListening(port)) {
                    // A server answers on the port but does not accept the superuser connection, so
                    // its data directory cannot be read. It is still a foreign server.
                    throw new IllegalStateException(unreachableServerMessage(port));
                }
                startOwnServer(port, dataDir, jdbcUrl, detach);
            }

            ensureAppDatabase(jdbcUrl, config);
        }

        System.out.println("POSTGRES_JDBC_URL=jdbc:postgresql://localhost:" + port + "/" + config.database());
        System.out.println("POSTGRES_READY");
        System.out.flush();

        if (detach) {
            // The server runs in its own process and keeps running; the shutdown hook that would
            // stop it is not registered in this mode. System.exit, because the threads of the
            // embedded server may keep the JVM alive otherwise.
            System.exit(0);
        }
        Thread.currentThread().join();
    }

    /**
     * Starts the embedded server on the given port and data directory. The data directory is kept
     * on shutdown, so that the role and the database survive a restart of the launcher.
     */
    private static void startOwnServer(int port, Path dataDir, String jdbcUrl, boolean detach)
            throws Exception {
        EmbeddedPostgres postgres;
        try {
            postgres = EmbeddedPostgres.builder()
                    .setPort(port)
                    .setDataDirectory(dataDir.toFile())
                    // The data directory is reused across runs: it holds the role of the application.
                    .setCleanDataDirectory(false)
                    // In the detached mode the server must outlive the launcher.
                    .setRegisterShutdownHook(!detach)
                    // The server inherits the OS locale on Windows and therefore answers in Russian.
                    // "C" keeps every server-side message in English.
                    .setServerConfig("lc_messages", "C")
                    .start();
        } catch (IOException | RuntimeException e) {
            // Two cases are possible:
            //  - the port is occupied by a foreign server: the postmaster failed to bind and the
            //    launcher must not attach to it;
            //  - a concurrent launcher of this project won the race for the data directory lock:
            //    its server owns the data directory and can be reused.
            String ownerDataDir = probeDataDirectory(jdbcUrl);
            if (ownerDataDir != null) {
                if (isSameDirectory(ownerDataDir, dataDir)) {
                    System.out.println(reuseMessage(port, ownerDataDir));
                    return;
                }
                throw new IllegalStateException(foreignServerMessage(port, ownerDataDir), e);
            }
            if (isPortListening(port)) {
                throw new IllegalStateException(unreachableServerMessage(port), e);
            }
            throw e;
        }

        String ownerDataDir = probeDataDirectory(jdbcUrl);
        if (ownerDataDir == null || !isSameDirectory(ownerDataDir, dataDir)) {
            postgres.close();
            throw new IllegalStateException("The PostgreSQL server that answered on port " + port
                    + " does not belong to this launcher (data directory: " + ownerDataDir + ").");
        }
    }

    private static String foreignServerMessage(int port, String ownerDataDir) {
        return "Port " + port + " is already used by another PostgreSQL server"
                + " (data directory: " + ownerDataDir + ")."
                + " Stop that server, or start this launcher on a different port"
                + " (system property '" + PORT_PROPERTY + "' or environment variable '"
                + LocalDatabaseConfig.PORT_ENV + "').";
    }

    private static String unreachableServerMessage(int port) {
        return "Port " + port + " is already used by a server that does not accept the '"
                + SUPERUSER + "' connection, so it cannot be checked whether it belongs to this launcher."
                + " Stop that server, or start this launcher on a different port"
                + " (system property '" + PORT_PROPERTY + "' or environment variable '"
                + LocalDatabaseConfig.PORT_ENV + "').";
    }

    private static String reuseMessage(int port, String ownerDataDir) {
        return "Reusing the PostgreSQL server of this launcher"
                + " (port " + port + ", data directory " + ownerDataDir + ").";
    }

    /**
     * Checks whether something accepts TCP connections on the given port. Used to tell "no server
     * here" apart from "a server here that cannot be inspected".
     */
    private static boolean isPortListening(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 1000);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }


    /**
     * Creates the role and the database that the application points to, so that the service starts
     * without any manual SQL. Both steps are idempotent: the server may already contain them from
     * an earlier run.
     */
    private static void ensureAppDatabase(String jdbcUrl, LocalDatabaseConfig config) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl + "/" + SUPERUSER, SUPERUSER, "");
             Statement statement = connection.createStatement()) {

            if (roleExists(statement, config.username())) {
                statement.execute("ALTER ROLE " + config.username()
                        + " WITH LOGIN PASSWORD '" + config.password() + "' CREATEDB");
            } else {
                statement.execute("CREATE ROLE " + config.username()
                        + " WITH LOGIN PASSWORD '" + config.password() + "' CREATEDB");
            }

            if (!databaseExists(statement, config.database())) {
                statement.execute("CREATE DATABASE " + config.database() + " OWNER " + config.username());
            }
        }
    }

    private static boolean roleExists(Statement statement, String role) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM pg_roles WHERE rolname = '" + role + "'")) {
            return rs.next();
        }
    }

    private static boolean databaseExists(Statement statement, String database) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM pg_database WHERE datname = '" + database + "'")) {
            return rs.next();
        }
    }

    /**
     * Returns the data directory of the PostgreSQL server that answers on the given JDBC URL, or
     * {@code null} if no server is reachable.
     */
    private static String probeDataDirectory(String jdbcUrl) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl + "/" + SUPERUSER, SUPERUSER, "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW data_directory")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    /** Compares two paths of a data directory, ignoring the trailing separator and the case. */
    private static boolean isSameDirectory(String left, Path right) {
        try {
            return Path.of(left).toAbsolutePath().normalize()
                    .equals(right.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The port of the embedded server. The system property of the launcher wins over the port of
     * the database configuration, which in turn already honours the environment variable
     * POSTGRES_PORT.
     */
    private static int resolvePort(int defaultPort) {
        String value = System.getProperty(PORT_PROPERTY);
        if (value == null || value.isBlank()) {
            return defaultPort;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port: " + value, e);
        }
    }

    private static Path resolveDataDir() {
        String value = System.getProperty(DATA_DIR_PROPERTY);
        if (value == null || value.isBlank()) {
            value = System.getenv(DATA_DIR_ENV);
        }
        if (value == null || value.isBlank()) {
            value = DEFAULT_DATA_DIR;
        }
        return new File(value).getAbsoluteFile().toPath().normalize();
    }

    /**
     * Returns the file that serialises the launchers of the given data directory. The lock file is
     * a sibling of the data directory: it must not live inside it, because {@code initdb} refuses
     * to work on a directory that is not empty.
     */
    private static Path lockFileFor(Path dataDir) {
        Path parent = dataDir.getParent();
        String name = dataDir.getFileName().toString() + ".lock";
        return parent == null ? Path.of(name) : parent.resolve(name);
    }

    /**
     * A file lock that serialises the launchers of one data directory across processes.
     */
    private static final class LauncherLock implements AutoCloseable {

        private final FileChannel channel;
        private final FileLock lock;

        private LauncherLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static LauncherLock acquire(Path lockFile) throws IOException {
            FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
            try {
                FileLock lock = null;
                while (lock == null) {
                    try {
                        lock = channel.tryLock();
                    } catch (java.nio.channels.OverlappingFileLockException e) {
                        // Another launcher of this JVM holds the lock: wait for it.
                        lock = null;
                    }
                    if (lock == null) {
                        Thread.sleep(200);
                    }
                }
                return new LauncherLock(channel, lock);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.close();
                throw new IOException("Interrupted while waiting for the launcher lock " + lockFile, e);
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
