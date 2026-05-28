package com.studyagent.modules.rag.application;

import com.studyagent.common.config.RagProperties;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.ai.ChatGenerationService;
import com.studyagent.infrastructure.embedding.EmbeddingService;
import com.studyagent.infrastructure.search.ElasticsearchChunkIndexer;
import com.studyagent.infrastructure.search.SearchHitChunk;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.modules.knowledge.domain.DocumentChunk;
import com.studyagent.modules.knowledge.infrastructure.DocumentChunkMapper;
import com.studyagent.modules.rag.domain.RagAnswer;
import com.studyagent.modules.rag.domain.RagReference;
import com.studyagent.modules.rag.domain.RagSearchResult;
import com.studyagent.modules.rag.domain.RrfRanker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * RAG 应用服务，编排混合检索、RRF 融合、父子上下文扩展和最终回答生成。
 */
@Service
@RequiredArgsConstructor
public class RagService {

    private static final Long DEFAULT_USER_ID = KnowledgeBaseService.DEFAULT_USER_ID;

    private final EmbeddingService embeddingService;
    private final ElasticsearchChunkIndexer elasticsearchChunkIndexer;
    private final DocumentChunkMapper documentChunkMapper;
    private final ChatGenerationService chatGenerationService;
    private final RagProperties ragProperties;

    /**
     * 面向接口的问答入口：无召回时返回明确提示，有召回时仅基于引用生成答案。
     */
    public RagAnswer answer(Long knowledgeBaseId, String question) {
        RagSearchResult searchResult = search(DEFAULT_USER_ID, List.of(knowledgeBaseId), question);
        if (searchResult.references().isEmpty()) {
            return new RagAnswer("知识库未检索到相关内容。", List.of());
        }
        String answer = chatGenerationService.generate(systemPrompt(), userPrompt(question, searchResult.references()));
        return new RagAnswer(answer, searchResult.references());
    }

    /**
     * 在用户和知识库范围内执行 BM25 + 向量混合检索，并返回可引用的 chunk。
     */
    public RagSearchResult search(Long userId, List<Long> knowledgeBaseIds, String question) {
        validateSearch(userId, knowledgeBaseIds, question);
        // 向量检索和关键词检索都带上用户与知识库范围，权限过滤不交给模型决定。
        float[] queryVector = embeddingService.embedQuery(question);
        List<SearchHitChunk> bm25Hits = elasticsearchChunkIndexer.bm25Search(
                userId,
                knowledgeBaseIds,
                question,
                ragProperties.bm25CandidateSize()
        );
        List<SearchHitChunk> vectorHits = elasticsearchChunkIndexer.vectorSearch(
                userId,
                knowledgeBaseIds,
                queryVector,
                ragProperties.vectorCandidateSize()
        );
        List<RrfRanker.RrfRankedItem> rankedItems = new RrfRanker(ragProperties.rrfK()).rank(List.of(
                candidates("bm25", bm25Hits),
                candidates("vector", vectorHits)
        ));
        if (rankedItems.isEmpty()) {
            return new RagSearchResult(question, List.of());
        }

        // 融合后再按 chunkId 补取最新 ES 内容，避免候选列表中缺少排序后的命中文本。
        Map<Long, SearchHitChunk> hitMap = new LinkedHashMap<>();
        putHits(hitMap, bm25Hits);
        putHits(hitMap, vectorHits);
        List<Long> topChunkIds = rankedItems.stream()
                .limit(ragProperties.topK())
                .map(RrfRanker.RrfRankedItem::chunkId)
                .toList();
        Map<Long, SearchHitChunk> freshHitMap = searchFreshHits(userId, topChunkIds);
        hitMap.putAll(freshHitMap);

        List<RagReference> fusedReferences = rankedItems.stream()
                .limit(ragProperties.topK())
                .map(item -> toReference(hitMap.get(item.chunkId()), "rrf", item.score()))
                .filter(reference -> reference != null)
                .toList();
        return new RagSearchResult(question, expandParentContext(fusedReferences));
    }

    /**
     * 校验检索必须带有明确用户、知识库范围和问题文本。
     */
    private void validateSearch(Long userId, List<Long> knowledgeBaseIds, String question) {
        if (userId == null) {
            throw new BusinessException("用户不能为空");
        }
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new BusinessException("知识库范围不能为空");
        }
        if (question == null || question.isBlank()) {
            throw new BusinessException("问题不能为空");
        }
    }

    /**
     * 将 ES 命中转换为 RRF 候选项，保留来源便于后续分析融合效果。
     */
    private List<RrfRanker.RrfCandidate> candidates(String source, List<SearchHitChunk> hits) {
        return hits.stream()
                .map(hit -> new RrfRanker.RrfCandidate(hit.chunkId(), source, hit.score()))
                .toList();
    }

    private void putHits(Map<Long, SearchHitChunk> hitMap, Collection<SearchHitChunk> hits) {
        for (SearchHitChunk hit : hits) {
            hitMap.putIfAbsent(hit.chunkId(), hit);
        }
    }

    /**
     * 根据 chunkId 补查 ES 中最新命中内容。
     */
    private Map<Long, SearchHitChunk> searchFreshHits(Long userId, List<Long> chunkIds) {
        Map<Long, SearchHitChunk> hitMap = new HashMap<>();
        List<SearchHitChunk> hits = elasticsearchChunkIndexer.searchByChunkIds(userId, chunkIds);
        for (SearchHitChunk hit : hits) {
            hitMap.put(hit.chunkId(), hit);
        }
        return hitMap;
    }

    /**
     * 将底层搜索命中转换成对外引用对象。
     */
    private RagReference toReference(SearchHitChunk hit, String retrievalSource, double score) {
        if (hit == null) {
            return null;
        }
        return new RagReference(
                hit.chunkId(),
                hit.documentId(),
                hit.knowledgeBaseId(),
                hit.parentChunkId(),
                hit.chunkIndex(),
                hit.documentTitle(),
                hit.content(),
                retrievalSource,
                score
        );
    }

    /**
     * 对融合后的种子 chunk 做窗口扩展，补足父子检索上下文。
     */
    private List<RagReference> expandParentContext(List<RagReference> fusedReferences) {
        Map<Long, RagReference> expanded = new LinkedHashMap<>();
        for (RagReference reference : fusedReferences) {
            addWindow(expanded, reference);
        }
        return new ArrayList<>(expanded.values()).stream()
                .limit(ragProperties.topK() * Math.max(1, ragProperties.parentBefore() + ragProperties.parentAfter() + 1))
                .toList();
    }

    /**
     * 添加种子 chunk 前后的上下文窗口，并标记非种子 chunk 来源为 parent_context。
     */
    private void addWindow(Map<Long, RagReference> expanded, RagReference seed) {
        int startIndex = Math.max(0, seed.chunkIndex() - ragProperties.parentBefore());
        int endIndex = seed.chunkIndex() + ragProperties.parentAfter();
        List<DocumentChunk> chunks = documentChunkMapper.selectWindow(seed.documentId(), startIndex, endIndex);
        for (DocumentChunk chunk : chunks) {
            expanded.putIfAbsent(chunk.getId(), new RagReference(
                    chunk.getId(),
                    chunk.getDocumentId(),
                    chunk.getKnowledgeBaseId(),
                    chunk.getParentChunkId(),
                    chunk.getChunkIndex(),
                    seed.documentTitle(),
                    chunk.getContent(),
                    chunk.getId().equals(seed.chunkId()) ? seed.retrievalSource() : "parent_context",
                    chunk.getId().equals(seed.chunkId()) ? seed.score() : Math.max(seed.score() * 0.8d, 0.0001d)
            ));
        }
    }

    /**
     * 约束模型只依据知识库引用回答，避免无依据生成。
     */
    private String systemPrompt() {
        return """
                你是一个面向学习备考场景的 AI 学习助手。你必须只依据用户提供的知识库引用回答。
                如果引用中没有答案，明确说“知识库未检索到相关内容”，不要编造。
                回答要结构清晰，适合学习者理解；必要时指出依据来自哪些引用编号。
                """;
    }

    /**
     * 构造带引用编号的用户提示词，方便模型在答案中标注依据。
     */
    private String userPrompt(String question, List<RagReference> references) {
        StringBuilder builder = new StringBuilder();
        builder.append("问题：").append(question).append("\n\n");
        builder.append("知识库引用：\n");
        for (int i = 0; i < references.size(); i++) {
            RagReference reference = references.get(i);
            builder.append("[引用").append(i + 1).append("]")
                    .append(" documentId=").append(reference.documentId())
                    .append(", chunkId=").append(reference.chunkId())
                    .append(", title=").append(reference.documentTitle())
                    .append("\n")
                    .append(reference.content())
                    .append("\n\n");
        }
        builder.append("请基于上述引用回答，并在关键结论后标注引用编号。");
        return builder.toString();
    }
}
