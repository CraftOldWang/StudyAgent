package com.studyagent.modules.rag.application;

import com.studyagent.config.RagProperties;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.ai.ChatGenerationService;
import com.studyagent.infrastructure.embedding.EmbeddingService;
import com.studyagent.infrastructure.search.ElasticsearchChunkIndexer;
import com.studyagent.infrastructure.search.SearchHitChunk;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.modules.rag.domain.RagAnswer;
import com.studyagent.modules.rag.domain.RagReference;
import com.studyagent.modules.rag.domain.RagSearchResult;
import com.studyagent.algo.rrf.RrfRanker;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * RAG 应用服务，编排混合检索、RRF 融合、父子检索和最终回答生成。
 */
@Service
@RequiredArgsConstructor
public class RagService {

    private static final Long DEFAULT_USER_ID = KnowledgeBaseService.DEFAULT_USER_ID;

    private final EmbeddingService embeddingService;
    private final ElasticsearchChunkIndexer elasticsearchChunkIndexer;
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
        return new RagSearchResult(question, loadParentContext(userId, knowledgeBaseIds, fusedReferences));
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
     *
     * <p>这里的引用仍然代表“命中的子 chunk”。后续父子检索会用 parentChunkId 取回父块内容，
     * 但保留子 chunkId 作为引用来源，便于审计、复习卡溯源和检索评测。</p>
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
     * 根据融合后的子 chunk 命中，从 Elasticsearch 批量取回父 chunk 内容。
     *
     * <p>真正的父子检索不是“命中点前后扩展”，而是：子 chunk 负责召回，父 chunk 负责上下文。
     * 子文档通过 parent_chunk_id 指向父文档的 chunk_id，第二跳仍然在 ES 内完成。
     * 多个命中落在同一父块时只返回一次父块，分数取该父块下最高命中分，避免把同一段上下文重复塞给模型。</p>
     */
    private List<RagReference> loadParentContext(
            Long userId,
            List<Long> knowledgeBaseIds,
            List<RagReference> childReferences
    ) {
        Map<Long, RagReference> bestChildByParent = new LinkedHashMap<>();
        for (RagReference childReference : childReferences) {
            Long parentChunkId = childReference.parentChunkId();
            if (parentChunkId == null) {
                // 兼容历史数据：没有 parent_chunk_id 的旧子块无法回表到父块，只能作为子块引用返回。
                bestChildByParent.putIfAbsent(childReference.chunkId(), childReference);
                continue;
            }
            bestChildByParent.merge(parentChunkId, childReference, (existing, candidate) ->
                    candidate.score() > existing.score() ? candidate : existing);
        }

        List<Long> parentChunkIds = bestChildByParent.keySet().stream().toList();
        if (parentChunkIds.isEmpty()) {
            return List.of();
        }
        Map<Long, SearchHitChunk> parentMap = new HashMap<>();
        for (SearchHitChunk parentChunk : elasticsearchChunkIndexer.searchParentChunks(userId, knowledgeBaseIds, parentChunkIds)) {
            parentMap.put(parentChunk.chunkId(), parentChunk);
        }

        return bestChildByParent.entrySet().stream()
                .map(entry -> toParentReference(entry.getKey(), entry.getValue(), parentMap.get(entry.getKey())))
                .limit(ragProperties.topK())
                .toList();
    }

    /**
     * 将父块内容包装成 RAG 引用。chunkId 继续保留命中的子块 ID，parentChunkId 指向实际上下文父块。
     */
    private RagReference toParentReference(Long parentChunkId, RagReference childReference, SearchHitChunk parentChunk) {
        if (parentChunk == null) {
            return childReference;
        }
        return new RagReference(
                childReference.chunkId(),
                parentChunk.documentId(),
                parentChunk.knowledgeBaseId(),
                parentChunkId,
                childReference.chunkIndex(),
                parentChunk.documentTitle(),
                parentChunk.content(),
                "parent_child_rrf",
                childReference.score()
        );
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
