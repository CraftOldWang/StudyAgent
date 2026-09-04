package com.studyagent.ingest.parse;

import com.studyagent.common.exception.BusinessException;
import java.io.InputStream;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

/**
 * 基于 Apache Tika 的文档文本解析器。
 */
@Component
public class TikaDocumentTextParser implements DocumentTextParser {

    /**
     * 解析输入流并返回非空文本，解析失败转换为明确业务异常。
     */
    @Override
    public String parse(InputStream inputStream) {
        try {
            String text = new Tika().parseToString(inputStream);
            if (text == null || text.isBlank()) {
                throw new BusinessException("文档未解析出有效文本");
            }
            return text;
        } catch (BusinessException ex) {
            throw ex;
        } catch (NoClassDefFoundError ex) {
            throw new BusinessException("文档解析组件依赖缺失: " + ex.getMessage());
        } catch (Exception ex) {
            throw new BusinessException("文档解析失败: " + ex.getMessage());
        }
    }
}
