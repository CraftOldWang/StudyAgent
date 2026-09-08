package com.studyagent.learning;

import static com.studyagent.learning.PlanningData.*;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.studyagent.agent.integration.AgentInvocationScopeFactory;
import com.studyagent.algo.chunk.JtokkitTokenCounter;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.LearningPlanningProperties;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.model.LearningPlanRun;
import com.studyagent.model.LearningPlanStage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LearningPlanningService {
    private final DocumentMapper documents;
    private final DocumentChunkMapper chunks;
    private final AgentInvocationScopeFactory scopeFactory;
    private final PlanningPersistence persistence;
    private final PlanningModel model;
    private final LearningTraceService traces;
    private final LearningPlanningProperties properties;
    private final ObjectMapper mapper;

    public LearningPlanRun create(Long userId, Long kbId, String goal, List<Long> lessonIds, List<Long> exerciseIds, Integer targetPointCount) {
        scopeFactory.validateKnowledgeBaseScope(userId, kbId);
        if (goal == null || goal.isBlank()) { throw new BusinessException("学习目标不能为空"); }
        if (targetPointCount != null && (targetPointCount < 1 || targetPointCount > 30)) {
            throw new BusinessException("目标知识点数量必须为 1–30");
        }
        List<Long> exercises = exerciseIds == null ? List.of() : List.copyOf(exerciseIds);
        List<Long> lessons = lessonIds == null ? documents.selectList(Wrappers.<Document>query()
                        .eq("user_id", userId).eq("knowledge_base_id", kbId).eq("pipeline_status", "INDEXED").orderByAsc("id"))
                .stream().map(Document::getId).filter(id -> !exercises.contains(id)).toList() : List.copyOf(lessonIds);
        if (lessons.isEmpty()) { throw new BusinessException("请至少选择一份已索引课件"); }
        if (new HashSet<>(lessons).size() != lessons.size() || new HashSet<>(exercises).size() != exercises.size()
                || lessons.stream().anyMatch(exercises::contains)) {
            throw new BusinessException("资料 ID 不可重复，课件与习题需分别选择");
        }
        Input input = new Input(PlanningModel.VERSION, targetPointCount, sources(userId, kbId, lessons), sources(userId, kbId, exercises));
        return persistence.create(userId, kbId, goal.trim(), json(input));
    }

    public View view(Long userId, Long runId) {
        LearningPlanRun run = persistence.require(userId, runId);
        Result result = null;
        LearningPlanStage tasks = persistence.latest(runId, "TASKS");
        if (tasks != null && "SUCCEEDED".equals(tasks.getStatus())) { result = decode(tasks.getOutputJson(), Result.class); }
        // Full source snapshots stay in storage; the UI loads provenance on demand rather than receiving every course page.
        return new View(run.getId(), run.getKnowledgeBaseId(), run.getLearningGoal(), run.getStatus(),
                run.getErrorMessage(), run.getSessionId(), persistence.listStages(runId).stream()
                .map(s -> new StageView(s.getId(), s.getStageKey(), s.getStatus(), s.getInputHash(), s.getAttemptCount(),
                        s.getTraceId(), s.getErrorMessage(), s.getStartedAt(), s.getCompletedAt(), s.getElapsedMillis(),
                        s.getUsageJson())).toList(), result);
    }

    public View execute(Long userId, Long runId) {
        LearningPlanRun run = persistence.require(userId, runId);
        if ("SUCCEEDED".equals(run.getStatus())) { return view(userId, runId); }
        String token = persistence.claim(run);
        try {
            Input input = decode(run.getInputJson(), Input.class);
            verifySnapshot(userId, run.getKnowledgeBaseId(), input);
            List<Candidate> candidates = new ArrayList<>();
            int index = 0;
            for (List<Source> batch : batches(input.lessons())) {
                String prompt = """
                        按当前课件片段提取学习知识点，保留概念、算法、前提及关键区别，通常每批 2–6 个点。
                        不把页眉、页码、目录文字单独当作知识点。当前目标仅决定详略，不能静默忽略整段资料。
                        作业提交、预习安排不是知识点；例题练习附属对应概念，不单列“预习作业”教学主题。
                        格式：{"points":[{"topic":"...","subtopics":["..."],"sourceChunkIds":["..."],
                        "evidence":[{"sourceChunkId":"...","quote":"原文摘录"}]}],
                        "uncovered":[{"sourceChunkId":"...","reason":"该片段仅含目录等，无可提取知识点"}]}。
                        每个输入 chunkId 至少被一个知识点引用或列入 uncovered；只使用本批次 ID。
                        每个知识点为每个引用来源提供1–2条简短原文依据，每条不超过400字，保留原文和标点，不能改写。
                        摘录覆盖该知识点的核心定义、机制或区别，用于后续判断习题是否直接考察该知识点。
                        目标：%s
                        课件片段：%s
                        """.formatted(run.getLearningGoal(), json(batch));
                Extraction extraction = stage(run, token, "EXTRACT/" + index++, prompt, Extraction.class,
                        node -> PlanningValidation.extraction(node, batch));
                candidates.addAll(extraction.points());
            }
            if (candidates.isEmpty()) { throw new BusinessException("选定课件没有可用于学习的大纲知识点"); }
            String mergePrompt = """
                    合并候选知识点，形成章节→知识点两层教学大纲，按先修概念在前排序。
                    每个候选 id 必须恰好出现一次；同义点、同一教学目标的相近子主题及其练习可合并，不能删除基础知识。
                    输出 points 数组的长度要求：%s。每个元素是一个最终知识点，chapterTitle 用于后续章节分组。
                    完整保留相关子主题再归并，不得编造课件未涉及的主题，也不把预习作业单列为知识点。
                    格式：{"points":[{"chapterTitle":"...","topic":"...","subtopics":["..."],
                    "candidateIds":["输入候选id"]}]}。不要嵌套 chapters 数组，服务端按 chapterTitle 分组。
                    先将全部候选分配到要求数量的最终知识点，再填写子主题，不能把每个候选都独立输出。
                    不要输出自己的教学 ID 或来源 ID，服务端会分配及汇总。
                    目标：%s
                    候选：%s
                    """.formatted(input.targetPointCount() == null ? "按资料内容合理确定" : "必须恰好 " + input.targetPointCount(),
                    run.getLearningGoal(), json(candidates));
            Outline outline = stage(run, token, "OUTLINE", mergePrompt, Outline.class,
                    node -> PlanningValidation.outline(node, candidates, input.targetPointCount()));
            List<Importance> matches = new ArrayList<>();
            List<Unmatched> unmatched = new ArrayList<>();
            index = 0;
            for (List<Source> batch : batches(input.exercises())) {
                String prompt = """
                        把当前批次往年习题的考察内容映射到既有大纲知识点，可一题关联多个点。
                        逐段处理，不能只看前几题。重点 HIGH 或 MEDIUM 仅代表这批资料给出的复习建议，不是未来考试概率。
                        reason 解释题目考察的概念与知识点关系；quote 必须原样摘录习题文字，禁止自行修正标点或公式。
                        每个匹配还必须提供 lessonSourceChunkId 和 lessonQuote，摘录课件中直接支持该知识点解题的具体内容。
                        lessonQuote 只能取大纲该知识点 evidence 中已提供的原文或其子串，没有直接依据的题目保留未匹配。
                        不得仅凭“都属于编译器”或“都会报告错误”将细分阶段问题笼统映射到编译器基本概念。
                        “有关系”不是“直接依据”。课件摘录必须具体描述解答题目所需的对象、操作或结论，
                        仅有上位概念、一般定义或前置背景时必须列入 unmatched，不能借助你自身的常识补齐后声称课件支持。
                        反例：课件只说“算法是解决问题的步骤”，题目问“快速排序的平均复杂度”，不可直接匹配。
                        正例：课件明确给出“快速排序平均复杂度为 O(n log n)”，才支持该问题的直接匹配。
                        对“在哪个具体阶段做某操作”类题目，课件必须明确包含该阶段与该操作的对应关系。
                        仅介绍分析/综合大类，不能推出词法/语法/语义/加载等具体阶段的职责，应保留为资料未覆盖。
                        对一道题的多个考察点分别处理；仅映射有课件直接依据的部分，其余部分列入 unmatched。
                        习题里的参考选项不保证正确，不照抄其正确性结论；只分析题目所考察的概念。
                        未匹配题目或非题目内容保留 unmatched 及原因，不强行匹配，不新造知识点 ID。
                        每个输入 chunkId 至少出现在 matches 或 unmatched，未涉及习题的基础知识点仍保留。
                        格式：{"matches":[{"knowledgePointId":"...","sourceChunkId":"...","quote":"...",
                        "lessonSourceChunkId":"...","lessonQuote":"...","reason":"...","priority":"HIGH"}],
                        "unmatched":[{"sourceChunkId":"...","quote":"...","reason":"..."}]}。
                        大纲：%s
                        习题片段：%s
                        """.formatted(json(outline), json(batch));
                Emphasis emphasis = stage(run, token, "EMPHASIS/" + index++, prompt, Emphasis.class,
                        node -> PlanningValidation.emphasis(node, outline, batch, input.lessons()));
                matches.addAll(emphasis.matches());
                unmatched.addAll(emphasis.unmatched());
            }
            Emphasis emphasis = new Emphasis(List.copyOf(matches), List.copyOf(unmatched));
            String taskPrompt = """
                    将既有大纲转换为有序学习任务，每个知识点 id 必须出现一次且仅一次，基础概念在应用之前。
                    根据目标和习题重点安排讲解与练习详略；理由说明顺序及内容安排，不预测未来考试。
                    给出不含重点追加的 baseMinutes(1–180整数)，服务端会为 HIGH 追加10分钟，MEDIUM追加5分钟。
                    格式：{"tasks":[{"knowledgePointId":"...","baseMinutes":15,"reason":"..."}]}。
                    目标：%s
                    大纲：%s
                    习题重点（空表示无习题依据）：%s
                    """.formatted(run.getLearningGoal(), json(outline), json(emphasis));
            stage(run, token, "TASKS", taskPrompt, Result.class,
                    node -> new Result(outline, emphasis, PlanningValidation.tasks(node, outline, emphasis)));
            persistence.complete(run, token);
            return view(userId, runId);
        } catch (RuntimeException error) {
            persistence.fail(run, token, error.getMessage());
            throw new BusinessException("规划任务 " + runId + " 未完成，可在原任务恢复：" + error.getMessage());
        }
    }

    private <T> T stage(LearningPlanRun run, String token, String key, String prompt, Class<T> type, Function<JsonNode, T> validate) {
        String input = json(Map.of("configuration", model.fingerprintConfiguration(), "system", PlanningModel.SYSTEM, "prompt", prompt));
        String hash = sha256(input);
        LearningPlanStage previous = persistence.latest(run.getId(), key);
        if (previous != null && "SUCCEEDED".equals(previous.getStatus()) && !hash.equals(previous.getInputHash())) {
            throw new BusinessException("规划阶段 " + key + " 输入或配置已变化，请新建任务，不能混用旧阶段");
        }
        if (previous != null && "SUCCEEDED".equals(previous.getStatus())) { return decode(previous.getOutputJson(), type); }
        String traceId = UUID.randomUUID().toString();
        LearningPlanStage stage = persistence.begin(run, token, key, hash, input, traceId);
        long started = System.nanoTime();
        traces.record(run.getUserId(), traceId, null, "PLAN", "MODEL_CALL", "run=" + run.getId() + ", stage=" + key, "STARTED");
        T output;
        try {
            PlanningModel.Completion completion = model.complete(traceId, "PLAN/" + run.getId() + "/" + key, prompt);
            stage.setRawOutput(completion.text());
            stage.setUsageJson(completion.usage() == null ? null : json(completion.usage()));
            output = validate.apply(strictObject(completion.text()));
            stage.setOutputJson(json(output));
            stage.setStatus("SUCCEEDED");
        } catch (RuntimeException error) {
            stage.setStatus("FAILED");
            stage.setErrorMessage(error.getMessage());
            finish(stage, token, started);
            traces.record(run.getUserId(), traceId, null, "PLAN", "STAGE_FAILURE", key + ": " + error.getMessage(), "FAILED");
            throw error;
        }
        finish(stage, token, started);
        traces.record(run.getUserId(), traceId, null, "PLAN", "STAGE_COMMIT", key + " 已校验并持久化", "SUCCEEDED");
        return output;
    }

    private void finish(LearningPlanStage stage, String token, long started) {
        stage.setCompletedAt(LocalDateTime.now());
        stage.setElapsedMillis((System.nanoTime() - started) / 1_000_000);
        persistence.finish(stage, token);
    }

    List<List<Source>> batches(List<Source> sources) {
        List<List<Source>> result = new ArrayList<>();
        List<Source> batch = new ArrayList<>();
        int tokens = 0;
        JtokkitTokenCounter counter = new JtokkitTokenCounter();
        for (Source source : sources) {
            int size = counter.count(source.content());
            if (size > properties.batchTokens()) { throw new BusinessException("父块超出规划单批预算，请检查切块配置"); }
            if (!batch.isEmpty() && (!batch.getFirst().documentId().equals(source.documentId()) || tokens + size > properties.batchTokens())) {
                result.add(List.copyOf(batch)); batch.clear(); tokens = 0;
            }
            batch.add(source); tokens += size;
        }
        if (!batch.isEmpty()) { result.add(List.copyOf(batch)); }
        return List.copyOf(result);
    }

    private List<Source> sources(Long userId, Long kbId, List<Long> ids) {
        List<Source> result = new ArrayList<>();
        for (Long id : ids) {
            Document doc = documents.selectOne(Wrappers.<Document>query().eq("id", id).eq("user_id", userId).eq("knowledge_base_id", kbId));
            if (doc == null || !"INDEXED".equals(doc.getPipelineStatus())) { throw new BusinessException("所选资料未索引或不属于当前知识库: " + id); }
            List<DocumentChunk> parents = chunks.selectList(Wrappers.<DocumentChunk>query().eq("document_id", id)
                    .eq("chunk_type", "PARENT").orderByAsc("chunk_index"));
            if (parents.isEmpty()) { throw new BusinessException("已索引资料缺少父块: " + id); }
            for (DocumentChunk chunk : parents) {
                result.add(new Source(id, doc.getTitle(), doc.getParsedTextHash(), chunk.getChunkId(), chunk.getContent(), chunk.getSourceLocation()));
            }
        }
        return List.copyOf(result);
    }

    private void verifySnapshot(Long userId, Long kbId, Input input) {
        List<Long> lessonIds = input.lessons().stream().map(Source::documentId).distinct().toList();
        List<Long> exerciseIds = input.exercises().stream().map(Source::documentId).distinct().toList();
        if (!input.lessons().equals(sources(userId, kbId, lessonIds)) || !input.exercises().equals(sources(userId, kbId, exerciseIds))) {
            throw new BusinessException("选定资料在规划后已变化，请新建任务");
        }
    }

    private JsonNode strictObject(String raw) {
        try {
            JsonNode value = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            if (value == null || !value.isObject()) { throw new BusinessException("规划输出必须为 JSON 对象"); }
            return value;
        } catch (JsonProcessingException e) { throw new BusinessException("规划输出不是严格 JSON: " + e.getOriginalMessage()); }
    }
    private String json(Object value) {
        try { return mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法序列化规划状态", e); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法读取已持久化规划状态", e); }
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public record StageView(Long id, String stage, String status, String inputHash, int attemptCount, String traceId,
                            String errorMessage, LocalDateTime startedAt, LocalDateTime completedAt, Long elapsedMillis, String usageJson) { }
    public record View(Long id, Long knowledgeBaseId, String learningGoal, String status, String errorMessage,
                       Long sessionId, List<StageView> stages, Result result) { }
}
