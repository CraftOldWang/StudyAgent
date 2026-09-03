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

class ReviewLearningSchemaMigrationIntegrationTest {

    private static final List<String> V3_TABLES = List.of(
            "learning_sessions",
            "learning_plan",
            "knowledge_points",
            "review_cards");

    @Test
    void migratesFreshDatabaseToReviewLearningSchema() throws Exception {
        String jdbcUrl = configured(
                "studyagent.migration-test.jdbc-url",
                "STUDYAGENT_MIGRATION_TEST_JDBC_URL",
                null);
        Assumptions.assumeTrue(jdbcUrl != null, "需要显式提供一次性 MySQL 测试库 JDBC URL");

        String username = configured(
                "studyagent.migration-test.username",
                "STUDYAGENT_MIGRATION_TEST_USERNAME",
                "root");
        String password = configured(
                "studyagent.migration-test.password",
                "STUDYAGENT_MIGRATION_TEST_PASSWORD",
                "");

        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            assertTables(connection);
            assertColumns(connection);
            assertIndexes(connection);
            assertStorageOptions(connection);
            assertFlywayHistory(connection);
        }
    }

    private void assertTables(Connection connection) throws SQLException {
        assertThat(queryStrings(connection, """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                ORDER BY table_name
                """)).contains(
                "knowledge_points",
                "learning_plan",
                "learning_sessions",
                "review_cards");
    }

    private void assertColumns(Connection connection) throws SQLException {
        assertThat(columns(connection, "learning_sessions")).containsExactly(
                column("id", "bigint", "NO", null),
                column("user_id", "bigint", "NO", null),
                column("knowledge_base_id", "bigint", "NO", null),
                column("agentscope_session_id", "varchar(128)", "NO", null),
                column("status", "varchar(32)", "NO", null),
                column("created_at", "datetime", "NO", null),
                column("updated_at", "datetime", "NO", null));

        assertThat(columns(connection, "learning_plan")).containsExactly(
                column("id", "bigint", "NO", null),
                column("session_id", "bigint", "NO", null),
                column("user_id", "bigint", "NO", null),
                column("plan_json", "json", "NO", null),
                column("created_at", "datetime", "NO", null));

        assertThat(columns(connection, "knowledge_points")).containsExactly(
                column("id", "bigint", "NO", null),
                column("session_id", "bigint", "NO", null),
                column("user_id", "bigint", "NO", null),
                column("topic", "varchar(512)", "NO", null),
                column("status", "varchar(32)", "NO", null),
                column("started_at", "datetime", "YES", null),
                column("completed_at", "datetime", "YES", null),
                column("created_at", "datetime", "NO", null),
                column("updated_at", "datetime", "NO", null));

        assertThat(columns(connection, "review_cards")).containsExactly(
                column("id", "bigint", "NO", null),
                column("user_id", "bigint", "NO", null),
                column("knowledge_point_id", "bigint", "NO", null),
                column("knowledge_base_id", "bigint", "NO", null),
                column("front", "text", "NO", null),
                column("back", "text", "NO", null),
                column("source_chunk_id", "varchar(64)", "YES", null),
                column("exported_to_anki", "tinyint(1)", "YES", "0"),
                column("anki_note_id", "bigint", "YES", null),
                column("created_at", "datetime", "NO", null));
    }

    private void assertIndexes(Connection connection) throws SQLException {
        assertThat(indexColumns(connection, "learning_sessions", "idx_user"))
                .containsExactly("user_id");
        assertThat(indexColumns(connection, "learning_sessions", "idx_as_session"))
                .containsExactly("agentscope_session_id");
        assertThat(indexColumns(connection, "learning_plan", "idx_session"))
                .containsExactly("session_id");
        assertThat(indexColumns(connection, "knowledge_points", "idx_session_status"))
                .containsExactly("session_id", "status");
        assertThat(indexColumns(connection, "review_cards", "idx_user"))
                .containsExactly("user_id");
        assertThat(indexColumns(connection, "review_cards", "idx_kp"))
                .containsExactly("knowledge_point_id");
    }

    private void assertStorageOptions(Connection connection) throws SQLException {
        for (String table : V3_TABLES) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT tables.engine,
                           tables.table_collation,
                           collations.character_set_name
                    FROM information_schema.tables AS tables
                    JOIN information_schema.collation_character_set_applicability AS collations
                      ON collations.collation_name = tables.table_collation
                    WHERE tables.table_schema = DATABASE() AND tables.table_name = ?
                    """)) {
                statement.setString(1, table);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).as(table).isTrue();
                    assertThat(resultSet.getString("engine")).isEqualToIgnoringCase("InnoDB");
                    assertThat(resultSet.getString("table_collation")).isEqualTo("utf8mb4_0900_ai_ci");
                    assertThat(resultSet.getString("character_set_name")).isEqualTo("utf8mb4");
                    assertThat(resultSet.next()).isFalse();
                }
            }
        }
    }

    private void assertFlywayHistory(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT version, description, type, success
                     FROM flyway_schema_history
                     WHERE version = '3'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("version")).isEqualTo("3");
            assertThat(resultSet.getString("description")).isEqualTo("create review learning tables");
            assertThat(resultSet.getString("type")).isEqualTo("SQL");
            assertThat(resultSet.getBoolean("success")).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    private List<ColumnDefinition> columns(Connection connection, String table) throws SQLException {
        List<ColumnDefinition> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name, column_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                ORDER BY ordinal_position
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(column(
                            resultSet.getString("column_name"),
                            resultSet.getString("column_type"),
                            resultSet.getString("is_nullable"),
                            resultSet.getString("column_default")));
                }
            }
        }
        return columns;
    }

    private List<String> indexColumns(Connection connection, String table, String index) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                ORDER BY seq_in_index
                """)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
                return columns;
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

    private ColumnDefinition column(String name, String type, String nullable, String defaultValue) {
        return new ColumnDefinition(name, type, nullable, defaultValue);
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

    private record ColumnDefinition(String name, String type, String nullable, String defaultValue) {
    }
}
