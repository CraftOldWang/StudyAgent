package com.studyagent.modules.learning.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.learning.domain.QuizAnswer;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface QuizAnswerMapper extends BaseMapper<QuizAnswer> {

    @Select("""
            SELECT *
            FROM quiz_answers
            WHERE question_id = #{questionId}
              AND user_id = #{userId}
            ORDER BY answered_at DESC
            """)
    List<QuizAnswer> selectByQuestion(
            @Param("questionId") Long questionId,
            @Param("userId") Long userId
    );
}
