package com.studyagent.rag.retrieval;

/**
 * 产生检索命中的单策略来源。
 */
public enum RetrievalStrategy {
    BM25,
    VECTOR,
    RRF
}
