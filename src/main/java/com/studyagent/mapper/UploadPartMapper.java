package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.model.UploadPart;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UploadPartMapper extends BaseMapper<UploadPart> {
    @Select("SELECT * FROM upload_parts WHERE upload_session_id=#{sessionId} ORDER BY chunk_index")
    List<UploadPart> listParts(@Param("sessionId") Long sessionId);
}
