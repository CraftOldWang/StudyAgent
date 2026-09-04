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

class IngestSchemaMigrationIntegrationTest {

    @Test
    void migratesFreshDatabaseToIngestSchema() throws Exception {
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
                "document_chunks",
                "documents",
                "file_records",
                "flyway_schema_history",
                "upload_sessions",
                "users");
    }

    private void assertColumns(Connection connection) throws SQLException {
        assertThat(columns(connection, "file_records")).containsExactly(
                column("id", "bigint", "NO"),
                column("user_id", "bigint", "NO"),
                column("knowledge_base_id", "bigint", "NO"),
                column("filename", "varchar(255)", "NO"),
                column("file_size", "bigint", "NO"),
                column("file_hash", "varchar(64)", "NO"),
                column("storage_key", "varchar(512)", "NO"),
                column("status", "varchar(32)", "NO"),
                column("created_at", "datetime", "NO"));

        assertThat(columns(connection, "documents")).containsExactly(
                column("id", "bigint", "NO"),
                column("file_record_id", "bigint", "NO"),
                column("user_id", "bigint", "NO"),
                column("knowledge_base_id", "bigint", "NO"),
                column("title", "varchar(512)", "YES"),
                column("content_type", "varchar(64)", "YES"),
                column("pipeline_status", "varchar(32)", "NO"),
                column("error_message", "text", "YES"),
                column("parser_version", "varchar(32)", "YES"),
                column("chunker_version", "varchar(32)", "YES"),
                column("created_at", "datetime", "NO"),
                column("updated_at", "datetime", "NO"));

        assertThat(columns(connection, "document_chunks")).containsExactly(
                column("id", "bigint", "NO"),
                column("document_id", "bigint", "NO"),
                column("chunk_id", "varchar(64)", "NO"),
                column("parent_chunk_id", "varchar(64)", "YES"),
                column("chunk_type", "varchar(16)", "NO"),
                column("chunk_index", "int", "NO"),
                column("content", "text", "NO"),
                column("content_hash", "varchar(64)", "NO"),
                column("source_location", "json", "YES"),
                column("embedding_status", "varchar(32)", "YES"),
                column("indexed_at", "datetime", "YES"),
                column("created_at", "datetime", "NO"));
    }

    private void assertIndexes(Connection connection) throws SQLException {
        assertThat(indexColumns(connection, "file_records", "idx_user_kb"))
                .containsExactly("user_id", "knowledge_base_id");
        assertThat(indexColumns(connection, "documents", "idx_user_kb"))
                .containsExactly("user_id", "knowledge_base_id");
        assertThat(indexColumns(connection, "documents", "idx_status"))
                .containsExactly("pipeline_status");
        assertThat(indexColumns(connection, "document_chunks", "idx_document"))
                .containsExactly("document_id");
        assertThat(indexColumns(connection, "document_chunks", "idx_parent"))
                .containsExactly("parent_chunk_id");
        assertThat(indexColumns(connection, "upload_sessions", "idx_upload_resume"))
                .containsExactly("user_id", "knowledge_base_id", "file_hash", "status", "expires_at");

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'document_chunks'
                  AND column_name = 'chunk_id'
                  AND non_unique = 0
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
            }
        }
    }

    private void assertStorageOptions(Connection connection) throws SQLException {
        for (String table : List.of("file_records", "documents", "document_chunks", "upload_sessions")) {
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
                     WHERE version IN ('1', '2')
                     ORDER BY installed_rank
                     """)) {
            assertMigration(resultSet, "1", "create users");
            assertMigration(resultSet, "2", "create ingest tables");
            assertThat(resultSet.next()).isFalse();
        }
    }

    private void assertMigration(ResultSet resultSet, String version, String description) throws SQLException {
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getString("version")).isEqualTo(version);
        assertThat(resultSet.getString("description")).isEqualTo(description);
        assertThat(resultSet.getString("type")).isEqualTo("SQL");
        assertThat(resultSet.getBoolean("success")).isTrue();
    }

    private List<ColumnDefinition> columns(Connection connection, String table) throws SQLException {
        List<ColumnDefinition> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name, column_type, is_nullable
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
                            resultSet.getString("is_nullable")));
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

    private ColumnDefinition column(String name, String type, String nullable) {
        return new ColumnDefinition(name, type, nullable);
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
