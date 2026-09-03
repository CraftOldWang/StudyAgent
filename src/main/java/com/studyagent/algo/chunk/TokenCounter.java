package com.studyagent.algo.chunk;

/**
 * 分块算法统一使用的本地 token 计量口径。
 */
public interface TokenCounter {

    int count(String text);
}
