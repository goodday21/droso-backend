package org.example.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the localization of the server messages.
 *
 * <p>On a Windows host the PostgreSQL server inherits the OS locale, so it answers in Russian.
 * Such a message is valid UTF-8, but a console that is not UTF-8 renders it as mojibake
 * ("я┐╜я┐╜"), which makes the logs unreadable. The server is therefore started with
 * {@code lc_messages=C}, and this test verifies that the messages really are in English.
 */
class ServerMessageLanguageTest {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        // The JDBC driver localizes the labels it adds itself ("Position: 15") with the locale of
        // the JVM. The application forces the English locale in Application.main; the same is
        // configured for the test task in build.gradle.kts. It is repeated here so that the test
        // also passes when it is started from the IDE.
        Locale.setDefault(Locale.ENGLISH);

        postgres = EmbeddedPostgres.builder()
                .setPort(0)
                .setServerConfig("lc_messages", "C")
                .start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    @DisplayName("the server reports its messages in English")
    void serverMessagesAreEnglish() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl("postgres", "postgres"), "postgres", "");
             Statement statement = connection.createStatement()) {

            try (var rs = statement.executeQuery("SHOW lc_messages")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("C");
            }
        }
    }

    @Test
    @DisplayName("an error message is English and free of non-ASCII characters")
    void errorMessageIsEnglish() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl("postgres", "postgres"), "postgres", "");
             Statement statement = connection.createStatement()) {

            statement.execute("SELECT * FROM table_that_does_not_exist");
            throw new AssertionError("the query was expected to fail");
        } catch (SQLException e) {
            assertThat(e.getMessage())
                    .contains("does not exist")
                    .doesNotContainPattern("[^\\x00-\\x7F]");
        }
    }

    @Test
    @DisplayName("an authentication error is English as well")
    void authenticationErrorIsEnglish() {
        // Not getJdbcUrl(): its URL carries "?user=postgres", which the driver prefers over the
        // user passed here, so the connection would succeed as "postgres".
        String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";
        try (Connection connection = DriverManager.getConnection(url, "role_that_does_not_exist", "")) {
            fail("the connection was expected to fail");
        } catch (SQLException e) {
            assertThat(e.getMessage())
                    .contains("does not exist")
                    .doesNotContainPattern("[^\\x00-\\x7F]");
        }
    }
}
