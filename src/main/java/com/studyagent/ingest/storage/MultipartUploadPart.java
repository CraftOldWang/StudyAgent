package com.studyagent.ingest.storage;

/**
 * S3 Multipart Upload 单个 part 的完成信息。
 *
 * <p>partNumber 使用 S3 约定的 1-based 编号；eTag 由对象存储在 uploadPart 成功后返回，
 * completeMultipartUpload 必须按 partNumber 升序提交这些 eTag，RustFS/S3 才能组装最终对象。</p>
 */
public record MultipartUploadPart(
        int partNumber,
        String eTag
) {
}
