package com.studyagent.ingest.web;

/**
 * 上传完成响应，返回文件记录、文档记录和本次上传结果状态。
 */
public record UploadResultResponse(
        Long fileId,
        Long documentId,
        String status
) {
}
