package com.studyagent.modules.rag.application;

import com.studyagent.common.config.RagProperties;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.embedding.EmbeddingService;
import com.studyagent.infrastructure.search.ElasticsearchChunkIndexer;
import com.studyagent.infrastructure.search.SearchHitChunk;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.modules.rag.domain.RagAnswer;
import com.studyagent.modules.rag.domain.RagReference;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RagService {

    private final EmbeddingService embeddingService;
    private final ElasticsearchChunkIndexer elasticsearchChunkIndexer;
    private final RagProperties ragProperties;

    public RagAnswer answer(Long knowledgeBaseId, String question) {
        if (question == null || question.isBlank()) {
            throw new BusinessException("问题不能为空");
        }

        float[] queryVector = embeddingService.embed(question);
        List<SearchHitChunk> hits = elasticsearchChunkIndexer.search(
                KnowledgeBaseService.DEFAULT_USER_ID,
                List.of(knowledgeBaseId),
                queryVector,
                ragProperties.topK()
        );
        if (hits.isEmpty()) {
            return new RagAnswer("知识库中未检索到相关内容。", List.of());
        }

        List<RagReference> references = hits.stream()
                .map(hit -> new RagReference(
                        hit.chunkId(),
                        hit.documentId(),
                        hit.knowledgeBaseId(),
                        hit.chunkIndex(),
                        hit.content(),
                        hit.score()))
                .toList();

        String answer = buildExtractiveAnswer(question, references);
        return new RagAnswer(answer, references);
    }

    private String buildExtractiveAnswer(String question, List<RagReference> references) {
        StringBuilder builder = new StringBuilder();
        builder.append("根据知识库检索结果，和“")
                .append(question)
                .append("”最相关的内容如下：\n\n");
        int limit = Math.min(3, references.size());
        for (int i = 0; i < limit; i++) {
            RagReference reference = references.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(compact(reference.content(), 320))
                    .append("\n\n");
        }
        builder.append("当前版本使用本地检索式回答，后续可以接入 Spring AI Alibaba 模型生成更自然的总结。");
        return builder.toString();
    }

    private String compact(String content, int maxLength) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}
