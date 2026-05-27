package com.studyagent.infrastructure.parser;

import com.studyagent.common.exception.BusinessException;
import java.io.InputStream;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

@Component
public class TikaDocumentTextParser implements DocumentTextParser {

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
