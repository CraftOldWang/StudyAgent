package com.studyagent.modules.evaluation.application;

import com.studyagent.modules.evaluation.domain.RagRetrievalStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 离线 Recall 评测请求。
 *
 * @param userId 用户范围，不传时使用默认演示用户。
 * @param knowledgeBaseIds 知识库范围，必须显式传入，避免评测时误跨库召回。
 * @param topKValues 要计算的 K 值，例如 [1,3,5,10]。
 * @param strategies 要对比的检索策略，不传时评测全部策略。
 * @param cases 评测样本。
 * @param includeCaseDetails 是否返回每条 case 的明细；样本很多时可以关闭以减少响应体。
 */
public record RagRecallEvaluationRequest(
        Long userId,
        @NotEmpty List<Long> knowledgeBaseIds,
        List<Integer> topKValues,
        List<RagRetrievalStrategy> strategies,
        @Valid @NotEmpty List<RagEvalCase> cases,
        Boolean includeCaseDetails
) {
}
