package com.studyagent.modules.learning.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.learning.domain.QuizAnswer;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 测验作答 Mapper，提供按题目查询历史作答能力。
 */
public interface QuizAnswerMapper extends BaseMapper<QuizAnswer> {

    /**
     * 查询用户对指定题目的作答记录。
     */
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
