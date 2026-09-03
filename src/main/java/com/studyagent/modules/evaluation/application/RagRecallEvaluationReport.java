package com.studyagent.modules.evaluation.application;

import com.studyagent.modules.evaluation.domain.RagRetrievalStrategy;
import java.util.List;
import java.util.Map;

/**
 * Recall 评测报告。
 *
 * <p>报告直接面向“最后只需要数据”的场景：接口返回即可复制到 Markdown、CSV 或简历量化描述中。
 * 后续如果需要持久化，也可以把整个 report 序列化进 rag_eval_runs.metrics_json。</p>
 */
public record RagRecallEvaluationReport(
        Long userId,
        List<Long> knowledgeBaseIds,
        int caseCount,
        List<Integer> topKValues,
        List<StrategyReport> strategies
) {

    /**
     * 单个检索策略的汇总指标。
     */
    public record StrategyReport(
            RagRetrievalStrategy strategy,
            Map<Integer, Double> seedRecallAtK,
            Map<Integer, Double> contextRecallAtK,
            double averageLatencyMillis,
            long totalLatencyMillis,
            List<CaseResult> cases
    ) {
    }

    /**
     * 单条评测样本的检索明细。
     */
    public record CaseResult(
            String question,
            List<String> expectedSeedChunkIds,
            List<String> expectedContextChunkIds,
            List<String> retrievedSeedChunkIds,
            List<String> retrievedContextChunkIds,
            Map<Integer, Double> seedRecallAtK,
            Map<Integer, Double> contextRecallAtK,
            long latencyMillis
    ) {
    }
}
