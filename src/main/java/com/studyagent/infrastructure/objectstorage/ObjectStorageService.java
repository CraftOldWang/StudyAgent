package com.studyagent.infrastructure.objectstorage;

import java.io.InputStream;
import java.util.List;

/**
 * 对象存储服务接口，业务模块通过它访问 S3 兼容存储。
 */
public interface ObjectStorageService {

    /**
     * 确保存储桶存在。
     */
    void ensureBucket();

    /**
     * 写入对象。
     */
    void putObject(String objectKey, InputStream inputStream, long contentLength, String contentType);

    /**
     * 初始化 S3 Multipart Upload，返回后续 uploadPart/complete 使用的 uploadId。
     */
    String initiateMultipartUpload(String objectKey, String contentType);

    /**
     * 上传 Multipart Upload 的单个 part。
     *
     * <p>partNumber 使用 S3 约定的 1-based 编号。调用方可以并发调用本方法，
     * 但 completeMultipartUpload 时必须把返回的 part 信息按 partNumber 升序提交。</p>
     */
    MultipartUploadPart uploadPart(
            String objectKey,
            String uploadId,
            int partNumber,
            InputStream inputStream,
            long contentLength
    );

    /**
     * 完成 S3 Multipart Upload，由对象存储在服务端组装最终对象。
     */
    void completeMultipartUpload(String objectKey, String uploadId, List<MultipartUploadPart> parts);

    /**
     * 放弃 S3 Multipart Upload，清理已经上传但尚未 complete 的 part。
     */
    void abortMultipartUpload(String objectKey, String uploadId);

    /**
     * 读取对象内容流，调用方负责关闭。
     */
    InputStream getObject(String objectKey);

    /**
     * 在同一 bucket 内复制对象。
     */
    void copyObject(String sourceKey, String targetKey);
}
