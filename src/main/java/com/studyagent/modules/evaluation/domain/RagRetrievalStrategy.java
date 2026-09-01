package com.studyagent.modules.evaluation.domain;

/**
 * RAG 召回评测使用的检索策略。
 *
 * <p>这些枚举值刻意对应面试/报告中最常见的对照组：先分别观察关键词与向量各自的能力，
 * 再观察 RRF 融合是否提升召回，最后观察父子上下文扩展是否让答案所需 chunk 被一起带回。</p>
 */
public enum RagRetrievalStrategy {
    /**
     * 只使用 BM25 关键词检索。
     */
    BM25_ONLY,

    /**
     * 只使用向量检索。
     */
    VECTOR_ONLY,

    /**
     * BM25 与向量双路召回后使用 RRF 融合，但不扩展父子上下文。
     */
    HYBRID_RRF,

    /**
     * BM25 与向量双路召回后使用 RRF 融合，并对种子 chunk 做前后窗口扩展。
     */
    HYBRID_RRF_PARENT
}
