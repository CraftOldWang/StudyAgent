package com.studyagent.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class UserSchemaMigrationIntegrationTest {

    private static final List<String> OLD_BUSINESS_TABLES = List.of(
            "agent_runs",
            "agent_step_records",
            "chat_context_snapshots",
            "chat_messages",
            "chat_sessions",
            "document_chunks",
            "documents",
            "file_records",
            "knowledge_bases",
            "learning_todos",
            "quiz_answers",
            "quiz_questions",
            "review_cards",
            "review_records",
            "tool_call_records",
            "upload_sessions"
    );

    @Test
    void migratesFreshDatabaseToUsersOnlyBaseline() throws Exception {
        String jdbcUrl = configured(
                "studyagent.migration-test.jdbc-url",
                "STUDYAGENT_MIGRATION_TEST_JDBC_URL",
                null
        );
        Assumptions.assumeTrue(jdbcUrl != null, "需要显式提供一次性 MySQL 测试库 JDBC URL");

        String username = configured(
                "studyagent.migration-test.username",
                "STUDYAGENT_MIGRATION_TEST_USERNAME",
                "root"
        );
        String password = configured(
                "studyagent.migration-test.password",
                "STUDYAGENT_MIGRATION_TEST_PASSWORD",
                ""
        );

        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            assertTables(connection);
            assertColumns(connection);
            assertUniqueUsername(connection);
            assertStorageOptions(connection);
            assertSeedUser(connection);
            assertFlywayHistory(connection);
            assertOldTablesAbsent(connection);
        }
    }

    private void assertTables(Connection connection) throws SQLException {
        List<String> tables = queryStrings(connection, """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                ORDER BY table_name
                """);
        assertThat(tables).containsExactly("flyway_schema_history", "users");
    }

    private void assertColumns(Connection connection) throws SQLException {
        List<ColumnDefinition> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name, column_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'users'
                ORDER BY ordinal_position
                """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                columns.add(new ColumnDefinition(
                        resultSet.getString("column_name"),
                        resultSet.getString("column_type"),
                        resultSet.getString("is_nullable")
                ));
            }
        }

        assertThat(columns).containsExactly(
                new ColumnDefinition("id", "bigint", "NO"),
                new ColumnDefinition("username", "varchar(64)", "NO"),
                new ColumnDefinition("created_at", "datetime", "NO"),
                new ColumnDefinition("updated_at", "datetime", "NO")
        );
    }

    private void assertUniqueUsername(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'users'
                  AND index_name = 'uk_users_username'
                  AND non_unique = 0
                  AND seq_in_index = 1
                  AND column_name = 'username'
                """);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            assertThat(resultSet.getInt(1)).isEqualTo(1);
        }
    }

    private void assertStorageOptions(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tables.engine,
                       tables.table_collation,
                       collations.character_set_name
                FROM information_schema.tables AS tables
                JOIN information_schema.collation_character_set_applicability AS collations
                  ON collations.collation_name = tables.table_collation
                WHERE tables.table_schema = DATABASE() AND tables.table_name = 'users'
                """);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("engine")).isEqualToIgnoringCase("InnoDB");
            assertThat(resultSet.getString("table_collation")).isEqualTo("utf8mb4_0900_ai_ci");
            assertThat(resultSet.getString("character_set_name")).isEqualTo("utf8mb4");
            assertThat(resultSet.next()).isFalse();
        }
    }

    private void assertSeedUser(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT id, username, created_at, updated_at
                     FROM users
                     ORDER BY id
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong("id")).isEqualTo(1L);
            assertThat(resultSet.getString("username")).isEqualTo("default-user");
            assertThat(resultSet.getTimestamp("created_at")).isNotNull();
            assertThat(resultSet.getTimestamp("updated_at")).isNotNull();
            assertThat(resultSet.next()).isFalse();
        }
    }

    private void assertFlywayHistory(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT version, description, type, success
                     FROM flyway_schema_history
                     ORDER BY installed_rank
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("version")).isEqualTo("1");
            assertThat(resultSet.getString("description")).isEqualTo("create users");
            assertThat(resultSet.getString("type")).isEqualTo("SQL");
            assertThat(resultSet.getBoolean("success")).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    private void assertOldTablesAbsent(Connection connection) throws SQLException {
        for (String table : OLD_BUSINESS_TABLES) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = ?
                    """)) {
                statement.setString(1, table);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    assertThat(resultSet.getInt(1)).as(table).isZero();
                }
            }
        }
    }

    private List<String> queryStrings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }

    private String configured(String propertyName, String environmentName, String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue.trim();
        }
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }
        return defaultValue;
    }

    private record ColumnDefinition(String name, String type, String nullable) {
    }
}
