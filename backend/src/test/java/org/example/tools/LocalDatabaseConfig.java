package org.example.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * The local database configuration, read from the single source of truth
 * {@code classpath:db-local.properties}.
 *
 * <p>The very same file is imported by the application ({@code application.yml} declares
 * {@code spring.config.import: classpath:db-local.properties}), so the launcher and the running
 * service always agree on the database. Neither of them declares the values a second time.
 *
 * <p>The environment variables {@code DB_URL}, {@code DB_USERNAME} and {@code DB_PASSWORD} override
 * the values of the file, exactly as they do in the application.
 *
 * <p>This class deliberately uses nothing but the JDK: the launcher runs as a plain {@code main}
 * method on the test runtime classpath, without a Spring context.
 */
public record LocalDatabaseConfig(String jdbcUrl, String username, String password) {

    /** The resource that holds the defaults of the local database. */
    public static final String RESOURCE = "db-local.properties";

    /**
     * The tracked template of the configuration. It is used as a fallback, so that the launcher
     * and the application still work when the working copy has not been created yet.
     */
    public static final String EXAMPLE_RESOURCE = "db-local.example.properties";

    /** Name of the environment variable that overrides the JDBC URL. */
    public static final String URL_ENV = "DB_URL";

    /** Name of the environment variable that overrides the user name. */
    public static final String USERNAME_ENV = "DB_USERNAME";

    /** Name of the environment variable that overrides the password. */
    public static final String PASSWORD_ENV = "DB_PASSWORD";

    /** Name of the environment variable that overrides the port of the local server. */
    public static final String PORT_ENV = "POSTGRES_PORT";

    /** The default port of PostgreSQL, used when the URL does not contain one. */
    private static final int DEFAULT_PORT = 5432;

    /**
     * Reads the configuration from the classpath and applies the environment overrides.
     */
    public static LocalDatabaseConfig load() {
        Properties properties = readProperties();

        // The environment variables DB_URL, DB_USERNAME and DB_PASSWORD override the values of the
        // file, exactly as they do in the application. The port of the launcher is overridden with
        // POSTGRES_PORT, so that the server it starts and the URL of the application agree.
        String jdbcUrl = override(composeUrl(properties), URL_ENV);
        String username = override(properties.getProperty("POSTGRES_USER"), USERNAME_ENV);
        String password = override(properties.getProperty("POSTGRES_PASSWORD"), PASSWORD_ENV);

        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("The property 'POSTGRES_HOST' is missing in " + RESOURCE);
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("The property 'POSTGRES_USER' is missing in " + RESOURCE);
        }
        return new LocalDatabaseConfig(jdbcUrl, username, password == null ? "" : password);
    }

    /**
     * Composes the JDBC URL from the parts of the file, exactly as the application does.
     */
    private static String composeUrl(Properties properties) {
        String host = properties.getProperty("POSTGRES_HOST");
        String port = override(properties.getProperty("POSTGRES_PORT"), PORT_ENV);
        String database = properties.getProperty("POSTGRES_DB");
        if (host == null || host.isBlank() || port == null || port.isBlank()
                || database == null || database.isBlank()) {
            return null;
        }
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    /**
     * Whether the database runs on this machine. The local launcher can only manage a server of
     * this machine.
     */
    public boolean isLocalHost() {
        String host = host();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    /**
     * The host of the database, e.g. {@code localhost} for
     * {@code jdbc:postgresql://localhost:5432/appdb}.
     */
    public String host() {
        String authority = authority();
        int colon = authority.lastIndexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }

    /**
     * The port of the database. Defaults to {@value #DEFAULT_PORT} if the URL does not contain one.
     */
    public int port() {
        String authority = authority();
        int colon = authority.lastIndexOf(':');
        if (colon < 0) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(authority.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port in the JDBC URL: " + jdbcUrl, e);
        }
    }

    /**
     * The name of the database, e.g. {@code appdb} for
     * {@code jdbc:postgresql://localhost:5432/appdb}.
     */
    public String database() {
        String path = path();
        if (path.isEmpty()) {
            throw new IllegalArgumentException("The JDBC URL does not contain a database name: " + jdbcUrl);
        }
        return path;
    }

    /**
     * The JDBC URL of the given database on the same server, e.g. used to reach the maintenance
     * database {@code postgres} of the same host with the superuser.
     */
    public String jdbcUrlOf(String database) {
        return "jdbc:postgresql://" + host() + ":" + port() + "/" + database;
    }

    private String authority() {
        String rest = afterPrefix();
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private String path() {
        String rest = afterPrefix();
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return "";
        }
        String path = rest.substring(slash + 1);
        int question = path.indexOf('?');
        return question < 0 ? path : path.substring(0, question);
    }

    private String afterPrefix() {
        String prefix = "jdbc:postgresql://";
        if (!jdbcUrl.startsWith(prefix)) {
            throw new IllegalArgumentException("Unsupported JDBC URL: " + jdbcUrl);
        }
        return jdbcUrl.substring(prefix.length());
    }

    /**
     * Returns the value of the environment variable if it is set, otherwise the value of the
     * property.
     */
    private static String override(String propertyValue, String environmentVariable) {
        String fromEnvironment = System.getenv(environmentVariable);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        return propertyValue;
    }

    private static Properties readProperties() {
        // The working copy of the developer wins; the tracked example is the fallback, so that a
        // fresh clone works without creating the file first.
        Properties properties = readProperties(RESOURCE);
        if (properties == null) {
            properties = readProperties(EXAMPLE_RESOURCE);
        }
        if (properties == null) {
            throw new IllegalStateException("Neither " + RESOURCE + " nor " + EXAMPLE_RESOURCE
                    + " is on the classpath. Copy " + EXAMPLE_RESOURCE + " to " + RESOURCE
                    + " and adjust it.");
        }
        return properties;
    }

    /**
     * Reads the given resource, or returns {@code null} when it does not exist.
     */
    private static Properties readProperties(String resource) {
        Properties properties = new Properties();
        try (InputStream stream = LocalDatabaseConfig.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            properties.load(stream);
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
    }
}
