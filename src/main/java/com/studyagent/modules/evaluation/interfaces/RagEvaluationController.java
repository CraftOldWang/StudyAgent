package com.studyagent.modules.evaluation.interfaces;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.modules.evaluation.application.DeepseekEvalCaseGenerationService;
import com.studyagent.modules.evaluation.application.GeneratedRagEvalDataset;
import com.studyagent.modules.evaluation.application.RagEvalCaseGenerationRequest;
import com.studyagent.modules.evaluation.application.RagRecallEvaluationReport;
import com.studyagent.modules.evaluation.application.RagRecallEvaluationRequest;
import com.studyagent.modules.evaluation.application.RagRecallEvaluationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 离线评测接口。
 *
 * <p>接口不写数据库，直接返回评测数据，适合在本地上传测试文档后快速跑对照实验。</p>
 */
@RestController
@RequestMapping("/api/evaluation/rag")
@RequiredArgsConstructor
public class RagEvaluationController {

    private final RagRecallEvaluationService ragRecallEvaluationService;
    private final DeepseekEvalCaseGenerationService deepseekEvalCaseGenerationService;

    /**
     * 根据给定 cases 计算多策略 Recall@K。
     */
    @PostMapping("/recall")
    public ApiResponse<RagRecallEvaluationReport> recall(@Valid @RequestBody RagRecallEvaluationRequest request) {
        return ApiResponse.ok(ragRecallEvaluationService.evaluate(request));
    }

    /**
     * 从已切分并索引的 chunk 中抽样，调用 DeepSeek 生成临时评测集。
     */
    @PostMapping("/cases/deepseek")
    public ApiResponse<GeneratedRagEvalDataset> generateCases(@Valid @RequestBody RagEvalCaseGenerationRequest request) {
        return ApiResponse.ok(deepseekEvalCaseGenerationService.generate(request));
    }
}
