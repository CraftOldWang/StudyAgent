package com.studyagent.modules.learning.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.learning.domain.LearningTodo;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 学习 Todo Mapper，提供学习 Agent 循环所需的当前知识点查询。
 */
public interface LearningTodoMapper extends BaseMapper<LearningTodo> {

    /**
     * 查询会话下的全部 Todo，按计划顺序返回。
     */
    @Select("""
            SELECT *
            FROM learning_todos
            WHERE session_id = #{sessionId}
            ORDER BY order_index ASC, id ASC
            """)
    List<LearningTodo> selectBySession(@Param("sessionId") Long sessionId);

    /**
     * 查询当前正在学习的知识点。
     */
    @Select("""
            SELECT *
            FROM learning_todos
            WHERE session_id = #{sessionId}
              AND status = 'LEARNING'
            ORDER BY order_index ASC, id ASC
            LIMIT 1
            """)
    LearningTodo selectCurrent(@Param("sessionId") Long sessionId);

    /**
     * 查询下一个待学习知识点。
     */
    @Select("""
            SELECT *
            FROM learning_todos
            WHERE session_id = #{sessionId}
              AND status = 'PENDING'
            ORDER BY order_index ASC, id ASC
            LIMIT 1
            """)
    LearningTodo selectNextPending(@Param("sessionId") Long sessionId);

    /**
     * 统计某会话的 Todo 数量，用于判断是否需要先执行 PLAN。
     */
    @Select("""
            SELECT COUNT(*)
            FROM learning_todos
            WHERE session_id = #{sessionId}
            """)
    long countBySession(@Param("sessionId") Long sessionId);
}
