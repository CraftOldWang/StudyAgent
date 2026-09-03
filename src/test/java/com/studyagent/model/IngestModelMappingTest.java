package com.studyagent.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.FileRecordMapper;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestModelMappingTest {

    @Test
    void fileRecordMatchesV2Schema() {
        assertModel(FileRecord.class, "file_records", fields(
                field("id", "id", Long.class),
                field("userId", "user_id", Long.class),
                field("knowledgeBaseId", "knowledge_base_id", Long.class),
                field("filename", "filename", String.class),
                field("fileSize", "file_size", Long.class),
                field("fileHash", "file_hash", String.class),
                field("storageKey", "storage_key", String.class),
                field("status", "status", String.class),
                field("createdAt", "created_at", LocalDateTime.class)));
    }

    @Test
    void documentMatchesV2Schema() {
        assertModel(Document.class, "documents", fields(
                field("id", "id", Long.class),
                field("fileRecordId", "file_record_id", Long.class),
                field("userId", "user_id", Long.class),
                field("knowledgeBaseId", "knowledge_base_id", Long.class),
                field("title", "title", String.class),
                field("contentType", "content_type", String.class),
                field("pipelineStatus", "pipeline_status", String.class),
                field("errorMessage", "error_message", String.class),
                field("parserVersion", "parser_version", String.class),
                field("chunkerVersion", "chunker_version", String.class),
                field("createdAt", "created_at", LocalDateTime.class),
                field("updatedAt", "updated_at", LocalDateTime.class)));
    }

    @Test
    void documentChunkMatchesV2Schema() {
        assertModel(DocumentChunk.class, "document_chunks", fields(
                field("id", "id", Long.class),
                field("documentId", "document_id", Long.class),
                field("chunkId", "chunk_id", String.class),
                field("parentChunkId", "parent_chunk_id", String.class),
                field("chunkType", "chunk_type", String.class),
                field("chunkIndex", "chunk_index", Integer.class),
                field("content", "content", String.class),
                field("contentHash", "content_hash", String.class),
                field("sourceLocation", "source_location", String.class),
                field("embeddingStatus", "embedding_status", String.class),
                field("indexedAt", "indexed_at", LocalDateTime.class),
                field("createdAt", "created_at", LocalDateTime.class)));
    }

    @Test
    void globalMappersBindToTheirV2Models() {
        assertMapper(FileRecordMapper.class, FileRecord.class);
        assertMapper(DocumentMapper.class, Document.class);
        assertMapper(DocumentChunkMapper.class, DocumentChunk.class);
    }

    private void assertModel(Class<?> model, String table, Map<String, ExpectedField> expected) {
        assertThat(model.getAnnotation(TableName.class).value()).isEqualTo(table);
        assertThat(model.getDeclaredFields()).extracting(Field::getName)
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        for (Field field : model.getDeclaredFields()) {
            ExpectedField expectedField = expected.get(field.getName());
            assertThat(field.getType()).as(field.getName()).isEqualTo(expectedField.type());
            String column = field.isAnnotationPresent(TableId.class)
                    ? field.getAnnotation(TableId.class).value()
                    : field.getAnnotation(TableField.class).value();
            assertThat(column).as(field.getName()).isEqualTo(expectedField.column());
        }
    }

    private void assertMapper(Class<?> mapper, Class<?> model) {
        ParameterizedType baseMapper = (ParameterizedType) mapper.getGenericInterfaces()[0];
        assertThat(baseMapper.getRawType()).isEqualTo(BaseMapper.class);
        assertThat(baseMapper.getActualTypeArguments()).containsExactly(model);
    }

    private Map<String, ExpectedField> fields(ExpectedField... fields) {
        Map<String, ExpectedField> expected = new LinkedHashMap<>();
        for (ExpectedField field : fields) {
            expected.put(field.name(), field);
        }
        return expected;
    }

    private ExpectedField field(String name, String column, Class<?> type) {
        return new ExpectedField(name, column, type);
    }

    private record ExpectedField(String name, String column, Class<?> type) {
    }
}
