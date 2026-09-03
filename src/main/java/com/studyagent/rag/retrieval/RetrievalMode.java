package com.studyagent.rag.retrieval;

/**
 * RetrievalService 对外支持的检索编排模式。
 */
public enum RetrievalMode {
    BM25,
    VECTOR,
    RRF,
    PARENT
}
