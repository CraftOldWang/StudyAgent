package com.studyagent.modules.learning.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.learning.domain.QuizQuestion;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 测验题 Mapper，提供题目历史查询。
 */
public interface QuizQuestionMapper extends BaseMapper<QuizQuestion> {

    /**
     * 查询当前用户的活跃题目历史，可按知识库过滤。
     */
    @Select("""
            <script>
            SELECT *
            FROM quiz_questions
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
            <if test="knowledgeBaseId != null">
              AND knowledge_base_id = #{knowledgeBaseId}
            </if>
            ORDER BY created_at DESC
            LIMIT #{limit}
            </script>
            """)
    List<QuizQuestion> selectHistory(
            @Param("userId") Long userId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("limit") int limit
    );
}
