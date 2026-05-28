package com.studyagent.modules.review.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.review.domain.ReviewCard;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 复习卡 Mapper，提供到期卡片查询。
 */
public interface ReviewCardMapper extends BaseMapper<ReviewCard> {

    /**
     * 查询当前用户指定时间前已经到期的活跃卡片。
     */
    @Select("""
            SELECT *
            FROM review_cards
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
              AND due_at <= #{dueAt}
            ORDER BY due_at ASC
            LIMIT #{limit}
            """)
    List<ReviewCard> selectDueCards(
            @Param("userId") Long userId,
            @Param("dueAt") LocalDateTime dueAt,
            @Param("limit") int limit
    );
}
