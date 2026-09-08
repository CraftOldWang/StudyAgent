package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuizAnswerParserTest {
    @Test void supportsNumberedChineseAndFullWidthChoicesInAnyOrder() {
        assertThat(QuizAnswerParser.parse("我的答案：5.Ａ，3:c；1、a，4）Ｄ，2-b"))
                .contains(List.of(0,1,2,3,0));
    }
    @Test void incompleteOrConflictingChoicesRequireClarification() {
        assertThat(QuizAnswerParser.parse("1A2B3C4D")).isEmpty();
        assertThat(QuizAnswerParser.parse("1A2B3C4D5A，1B")).isEmpty();
        assertThat(QuizAnswerParser.parse("请解释第1题，A和B有什么区别？")).isEmpty();
        assertThat(QuizAnswerParser.parse("11A2B3C4D5A")).isEmpty();
    }
}
