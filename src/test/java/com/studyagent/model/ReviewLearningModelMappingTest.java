package com.studyagent.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.mapper.KnowledgePointMapper;
import com.studyagent.mapper.AgentTraceEventMapper;
import com.studyagent.mapper.LearningPlanMapper;
import com.studyagent.mapper.LearningSessionMapper;
import com.studyagent.mapper.QuizMapper;
import com.studyagent.mapper.ReviewCardMapper;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewLearningModelMappingTest {

    @Test
    void learningSessionMatchesV3Schema() {
        assertModel(LearningSession.class, "learning_sessions", fields(
                field("id", "id", Long.class),
                field("userId", "user_id", Long.class),
                field("knowledgeBaseId", "knowledge_base_id", Long.class),
                field("learningGoal", "learning_goal", String.class),
                field("agentscopeSessionId", "agentscope_session_id", String.class),
                field("activeKnowledgePointId", "active_knowledge_point_id", Long.class),
                field("status", "status", String.class),
                field("errorMessage", "error_message", String.class),
                field("createdAt", "created_at", LocalDateTime.class),
                field("updatedAt", "updated_at", LocalDateTime.class)));
    }

    @Test
    void learningPlanMatchesV3Schema() {
        assertModel(LearningPlan.class, "learning_plan", fields(
                field("id", "id", Long.class),
                field("sessionId", "session_id", Long.class),
                field("userId", "user_id", Long.class),
                field("planJson", "plan_json", String.class),
                field("createdAt", "created_at", LocalDateTime.class)));
    }

    @Test
    void knowledgePointMatchesV3Schema() {
        assertModel(KnowledgePoint.class, "knowledge_points", fields(
                field("id", "id", Long.class),
                field("sessionId", "session_id", Long.class),
                field("userId", "user_id", Long.class),
                field("sequenceNo", "sequence_no", Integer.class),
                field("topic", "topic", String.class),
                field("subtopicsJson", "subtopics_json", String.class),
                field("estimatedMinutes", "estimated_minutes", Integer.class),
                field("status", "status", String.class),
                field("explanation", "explanation", String.class),
                field("errorMessage", "error_message", String.class),
                field("startedAt", "started_at", LocalDateTime.class),
                field("completedAt", "completed_at", LocalDateTime.class),
                field("createdAt", "created_at", LocalDateTime.class),
                field("updatedAt", "updated_at", LocalDateTime.class)));
    }

    @Test
    void reviewCardMatchesV3Schema() {
        assertModel(ReviewCard.class, "review_cards", fields(
                field("id", "id", Long.class),
                field("userId", "user_id", Long.class),
                field("knowledgePointId", "knowledge_point_id", Long.class),
                field("knowledgeBaseId", "knowledge_base_id", Long.class),
                field("front", "front", String.class),
                field("back", "back", String.class),
                field("sourceChunkId", "source_chunk_id", String.class),
                field("exportedToAnki", "exported_to_anki", Boolean.class),
                field("ankiNoteId", "anki_note_id", Long.class),
                field("createdAt", "created_at", LocalDateTime.class)));
    }

    @Test
    void globalMappersBindToTheirV3Models() {
        assertMapper(LearningSessionMapper.class, LearningSession.class);
        assertMapper(LearningPlanMapper.class, LearningPlan.class);
        assertMapper(KnowledgePointMapper.class, KnowledgePoint.class);
        assertMapper(ReviewCardMapper.class, ReviewCard.class);
        assertMapper(QuizMapper.class, Quiz.class);
        assertMapper(AgentTraceEventMapper.class, AgentTraceEvent.class);
    }

    @Test
    void quizMatchesM2Schema() {
        assertModel(Quiz.class, "quizzes", fields(
                field("id", "id", Long.class),
                field("userId", "user_id", Long.class),
                field("sessionId", "session_id", Long.class),
                field("knowledgePointId", "knowledge_point_id", Long.class),
                field("questionsJson", "questions_json", String.class),
                field("answersJson", "answers_json", String.class),
                field("score", "score", Integer.class),
                field("feedbackJson", "feedback_json", String.class),
                field("createdAt", "created_at", LocalDateTime.class),
                field("answeredAt", "answered_at", LocalDateTime.class)));
    }

    @Test
    void traceEventMatchesM2Schema() {
        assertModel(AgentTraceEvent.class, "agent_trace_events", fields(
                field("id", "id", Long.class),
                field("userId", "user_id", Long.class),
                field("traceId", "trace_id", String.class),
                field("sessionId", "session_id", Long.class),
                field("sequenceNo", "sequence_no", Integer.class),
                field("stage", "stage", String.class),
                field("eventType", "event_type", String.class),
                field("summary", "summary", String.class),
                field("status", "status", String.class),
                field("createdAt", "created_at", LocalDateTime.class)));
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
