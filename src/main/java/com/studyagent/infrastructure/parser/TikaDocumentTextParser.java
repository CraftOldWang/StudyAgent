package com.studyagent.infrastructure.parser;

import com.studyagent.common.exception.BusinessException;
import java.io.InputStream;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

@Component
public class TikaDocumentTextParser implements DocumentTextParser {

    private final Tika tika = new Tika();

    @Override
    public String parse(InputStream inputStream) {
        try {
            String text = tika.parseToString(inputStream);
            if (text == null || text.isBlank()) {
                throw new BusinessException("文档未解析出有效文本");
            }
            return text;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("文档解析失败: " + ex.getMessage());
        }
    }
}
