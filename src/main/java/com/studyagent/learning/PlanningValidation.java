package com.studyagent.learning;

import static com.studyagent.learning.PlanningData.*;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.studyagent.common.exception.BusinessException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validate generated relationships before assigning durable teaching IDs or publishing a plan. */
public final class PlanningValidation {
    private PlanningValidation() { }

    public static Extraction extraction(JsonNode root, List<Source> sources) {
        Set<String> allowed = sources.stream().map(Source::chunkId).collect(Collectors.toSet());
        Map<String, Source> sourceMap = sources.stream().collect(Collectors.toMap(Source::chunkId, s -> s));
        Set<String> covered = new HashSet<>();
        List<Candidate> points = new ArrayList<>();
        for (JsonNode node : array(root, "points")) {
            List<String> refs = strings(node, "sourceChunkIds");
            require(!refs.isEmpty() && allowed.containsAll(refs), "大纲提取引用了本批次以外的来源");
            List<Evidence> evidence = new ArrayList<>();
            for (JsonNode item : array(node, "evidence")) {
                String source = text(item, "sourceChunkId");
                String quote = text(item, "quote");
                require(refs.contains(source) && quote.length() <= 400, "知识点依据必须使用引用来源且摘录不超过400字");
                quotedSource(sourceMap, source, quote);
                evidence.add(new Evidence(source, quote));
            }
            require(evidence.stream().map(Evidence::sourceChunkId).collect(Collectors.toSet()).equals(new HashSet<>(refs)),
                    "知识点每个引用来源都需要原文依据");
            covered.addAll(refs);
            points.add(new Candidate(IdWorker.getId(), text(node, "topic"), strings(node, "subtopics"), refs, List.copyOf(evidence)));
        }
        List<Uncovered> uncovered = new ArrayList<>();
        Set<String> noted = new HashSet<>();
        for (JsonNode node : array(root, "uncovered")) {
            String id = text(node, "sourceChunkId");
            // One retrieval parent may contain both useful prose and unparsed image placeholders.
            require(allowed.contains(id) && noted.add(id), "未提取说明必须属于本批次且不得重复");
            covered.add(id);
            uncovered.add(new Uncovered(id, text(node, "reason")));
        }
        require(covered.equals(allowed), "大纲提取遗漏输入来源，必须列出知识点或未提取原因");
        return new Extraction(List.copyOf(points), List.copyOf(uncovered));
    }

    public static Outline outline(JsonNode root, List<Candidate> candidates, Integer targetPointCount) {
        Map<Long, Candidate> allowed = candidates.stream().collect(Collectors.toMap(Candidate::id, c -> c));
        Set<Long> consumed = new HashSet<>();
        JsonNode generated = array(root, "points");
        require(targetPointCount == null || generated.size() == targetPointCount,
                "大纲知识点数量与用户指定数量不一致");
        Map<String, List<Point>> byChapter = new java.util.LinkedHashMap<>();
        for (JsonNode point : generated) {
            LinkedHashSet<String> refs = new LinkedHashSet<>();
            LinkedHashSet<Evidence> evidence = new LinkedHashSet<>();
            for (JsonNode value : array(point, "candidateIds")) {
                Long id = id(value);
                require(allowed.containsKey(id) && consumed.add(id), "大纲合并引用未知或重复候选知识点");
                refs.addAll(allowed.get(id).sourceChunkIds());
                evidence.addAll(allowed.get(id).evidence());
            }
            require(!refs.isEmpty(), "合并知识点必须有候选来源");
            byChapter.computeIfAbsent(text(point, "chapterTitle"), key -> new ArrayList<>()).add(
                    new Point(IdWorker.getId(), text(point, "topic"), strings(point, "subtopics"), List.copyOf(refs), List.copyOf(evidence)));
        }
        require(!byChapter.isEmpty() && consumed.equals(allowed.keySet()), "合并大纲必须保留全部候选知识点，可合并同义项");
        return new Outline(byChapter.entrySet().stream()
                .map(entry -> new Chapter(IdWorker.getId(), entry.getKey(), List.copyOf(entry.getValue()))).toList());
    }

    public static Emphasis emphasis(JsonNode root, Outline outline, List<Source> exercises, List<Source> lessons) {
        Set<Long> pointIds = outline.chapters().stream().flatMap(c -> c.points().stream()).map(Point::id).collect(Collectors.toSet());
        Map<String, Source> sources = exercises.stream().collect(Collectors.toMap(Source::chunkId, s -> s));
        Map<String, Source> lessonSources = lessons.stream().collect(Collectors.toMap(Source::chunkId, s -> s));
        Map<Long, Point> points = outline.chapters().stream().flatMap(c -> c.points().stream()).collect(Collectors.toMap(Point::id, p -> p));
        Set<String> covered = new HashSet<>();
        List<Importance> matches = new ArrayList<>();
        Set<String> duplicates = new HashSet<>();
        for (JsonNode node : array(root, "matches")) {
            Long point = id(node.get("knowledgePointId"));
            require(pointIds.contains(point), "习题重点只能映射到既有知识点");
            String source = text(node, "sourceChunkId");
            String quote = text(node, "quote");
            quotedSource(sources, source, quote);
            String lessonSource = text(node, "lessonSourceChunkId");
            String lessonQuote = text(node, "lessonQuote");
            require(points.get(point).sourceChunkIds().contains(lessonSource), "课件摘录必须属于被映射知识点的来源");
            quotedSource(lessonSources, lessonSource, lessonQuote);
            require(points.get(point).evidence().stream().anyMatch(e -> e.sourceChunkId().equals(lessonSource)
                    && normalize(e.quote()).contains(normalize(lessonQuote))), "重点依据必须来自该知识点已确认的摘录");
            String priority = text(node, "priority");
            require(Set.of("HIGH", "MEDIUM").contains(priority), "习题重点必须为 HIGH 或 MEDIUM");
            require(duplicates.add(point + "/" + source + "/" + normalize(quote)), "重复习题映射");
            matches.add(new Importance(point, source, quote, lessonSource, lessonQuote, text(node, "reason"), priority));
            covered.add(source);
        }
        List<Unmatched> unmatched = new ArrayList<>();
        for (JsonNode node : array(root, "unmatched")) {
            String source = text(node, "sourceChunkId");
            String quote = text(node, "quote");
            quotedSource(sources, source, quote);
            unmatched.add(new Unmatched(source, quote, text(node, "reason")));
            covered.add(source);
        }
        require(covered.equals(sources.keySet()), "习题批次有未处理的来源，需要保留未匹配说明");
        return new Emphasis(List.copyOf(matches), List.copyOf(unmatched));
    }

    public static List<Task> tasks(JsonNode root, Outline outline, Emphasis emphasis) {
        Map<Long, Point> points = new HashMap<>();
        Map<Long, Chapter> chapters = new HashMap<>();
        outline.chapters().forEach(c -> c.points().forEach(p -> { points.put(p.id(), p); chapters.put(p.id(), c); }));
        Map<Long, String> priorities = new HashMap<>();
        emphasis.matches().forEach(m -> priorities.merge(m.knowledgePointId(), m.priority(),
                (a, b) -> "HIGH".equals(a) || "HIGH".equals(b) ? "HIGH" : "MEDIUM"));
        List<Task> tasks = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (JsonNode node : array(root, "tasks")) {
            Long id = id(node.get("knowledgePointId"));
            require(points.containsKey(id) && used.add(id), "任务必须使用既有且不重复的知识点 ID");
            JsonNode minutes = node.get("baseMinutes");
            require(minutes != null && minutes.isIntegralNumber() && minutes.canConvertToInt()
                    && minutes.intValue() > 0 && minutes.intValue() <= 180, "baseMinutes 必须为 1–180 的整数");
            String priority = priorities.getOrDefault(id, "NORMAL");
            int allocated = minutes.intValue() + ("HIGH".equals(priority) ? 10 : "MEDIUM".equals(priority) ? 5 : 0);
            Point p = points.get(id);
            Chapter c = chapters.get(id);
            tasks.add(new Task(id, c.id(), c.title(), p.topic(), p.subtopics(), p.sourceChunkIds(), priority,
                    allocated, text(node, "reason")));
        }
        require(used.equals(points.keySet()), "计划不能删除未被习题覆盖的基础知识点");
        return List.copyOf(tasks);
    }

    private static void quotedSource(Map<String, Source> sources, String id, String quote) {
        require(sources.containsKey(id) && normalize(sources.get(id).content()).contains(normalize(quote)),
                "习题引用必须是当前批次资料中的原文摘录");
    }
    private static String normalize(String text) { return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", ""); }
    private static Long id(JsonNode node) {
        try {
            require(node != null && (node.isTextual() || node.isIntegralNumber()), "ID 必须是整数或整数字符串");
            Long id = Long.valueOf(node.asText());
            require(id > 0, "ID 必须为正整数");
            return id;
        } catch (NumberFormatException e) { throw new BusinessException("非法教学 ID"); }
    }
    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isArray(), field + " 必须是数组");
        return value;
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual() && !value.asText().isBlank(), field + " 必须是非空文本");
        return value.asText().trim();
    }
    private static List<String> strings(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : array(node, field)) {
            require(value.isTextual() && !value.asText().isBlank(), field + " 必须仅含非空文本");
            require(!result.contains(value.asText().trim()), field + " 不得重复");
            result.add(value.asText().trim());
        }
        return List.copyOf(result);
    }
    private static void require(boolean accepted, String message) { if (!accepted) { throw new BusinessException(message); } }
}
