package com.studyagent.modules.evaluation.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 评测集 JSON 解析器。
 *
 * <p>模型即使开启 JSON 输出，也可能返回不符合业务约束的 chunkId。
 * 这里会做显式校验，并把被丢弃的异常样本写入 warnings，避免“看起来生成成功但真值不可用”。</p>
 */
@Component
public class DeepseekGeneratedCasesParser {

    private final ObjectMapper objectMapper;

    public DeepseekGeneratedCasesParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析模型返回的 JSON content。
     */
    public ParseResult parse(String jsonContent, List<GeneratedRagEvalDataset.SourceChunk> sourceChunks) {
        if (jsonContent == null || jsonContent.isBlank()) {
            throw new BusinessException("DeepSeek 未返回评测集内容");
        }
        Set<Long> allowedChunkIds = new LinkedHashSet<>();
        for (GeneratedRagEvalDataset.SourceChunk sourceChunk : sourceChunks) {
            allowedChunkIds.add(sourceChunk.chunkId());
        }
        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            JsonNode casesNode = root.path("cases");
            if (!casesNode.isArray()) {
                throw new BusinessException("DeepSeek 返回 JSON 缺少 cases 数组");
            }
            List<RagEvalCase> cases = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            int index = 0;
            for (JsonNode caseNode : casesNode) {
                index++;
                String question = caseNode.path("question").asText("");
                String expectedAnswer = caseNode.path("expectedAnswer").asText(null);
                List<Long> expectedChunkIds = readExpectedChunkIds(caseNode.path("expectedChunkIds"));
                List<Long> validChunkIds = expectedChunkIds.stream()
                        .filter(allowedChunkIds::contains)
                        .distinct()
                        .toList();
                if (question.isBlank() || validChunkIds.isEmpty()) {
                    warnings.add("第 " + index + " 条生成样本缺少问题或有效 expectedChunkIds，已丢弃");
                    continue;
                }
                if (validChunkIds.size() != new LinkedHashSet<>(expectedChunkIds).size()) {
                    warnings.add("第 " + index + " 条生成样本包含候选 chunk 之外的 ID，已过滤");
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("source", "deepseek");
                metadata.put("reason", caseNode.path("reason").asText(""));
                cases.add(new RagEvalCase(question, validChunkIds, expectedAnswer, metadata));
            }
            return new ParseResult(cases, warnings);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("解析 DeepSeek 评测集 JSON 失败: " + ex.getMessage());
        }
    }

    /**
     * 读取 expectedChunkIds，非数字字段会被忽略并由上层通过 warnings 暴露异常。
     */
    private List<Long> readExpectedChunkIds(JsonNode expectedChunkIdsNode) {
        if (!expectedChunkIdsNode.isArray()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode idNode : expectedChunkIdsNode) {
            if (idNode.canConvertToLong()) {
                ids.add(idNode.asLong());
            }
        }
        return ids;
    }

    /**
     * 解析结果和显式告警。
     */
    public record ParseResult(
            List<RagEvalCase> cases,
            List<String> warnings
    ) {
    }
}
