package com.studyagent.ingest.parse;

import java.io.InputStream;

/**
 * 文档文本解析接口，屏蔽 PDF、Word、Markdown 等具体解析实现。
 */
public interface DocumentTextParser {

    /**
     * 从输入流中解析纯文本。
     */
    String parse(InputStream inputStream);
}
