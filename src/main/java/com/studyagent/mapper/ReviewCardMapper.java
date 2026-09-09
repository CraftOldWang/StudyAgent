package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.model.ReviewCard;

public interface ReviewCardMapper extends BaseMapper<ReviewCard> {
    @org.apache.ibatis.annotations.Update("UPDATE review_cards SET anki_export_status='EXPORTING', "
            + "anki_export_error=NULL, anki_export_attempts=anki_export_attempts+1 WHERE id=#{id} AND user_id=#{userId}")
    int beginExport(@org.apache.ibatis.annotations.Param("userId") Long userId,
                    @org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Update("UPDATE review_cards SET anki_export_status='SUCCEEDED', "
            + "anki_export_error=NULL, exported_to_anki=1, anki_note_id=#{noteId}, anki_exported_at=#{now} "
            + "WHERE id=#{id} AND user_id=#{userId}")
    int exportSucceeded(@org.apache.ibatis.annotations.Param("userId") Long userId,
                        @org.apache.ibatis.annotations.Param("id") Long id,
                        @org.apache.ibatis.annotations.Param("noteId") Long noteId,
                        @org.apache.ibatis.annotations.Param("now") java.time.LocalDateTime now);

    @org.apache.ibatis.annotations.Update("UPDATE review_cards SET anki_export_status='FAILED', "
            + "anki_export_error=#{error} WHERE id=#{id} AND user_id=#{userId}")
    int exportFailed(@org.apache.ibatis.annotations.Param("userId") Long userId,
                     @org.apache.ibatis.annotations.Param("id") Long id,
                     @org.apache.ibatis.annotations.Param("error") String error);
}
