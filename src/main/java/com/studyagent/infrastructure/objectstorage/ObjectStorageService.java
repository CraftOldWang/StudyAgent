package com.studyagent.infrastructure.objectstorage;

import java.io.InputStream;

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
     * 读取对象内容流，调用方负责关闭。
     */
    InputStream getObject(String objectKey);

    /**
     * 在同一 bucket 内复制对象。
     */
    void copyObject(String sourceKey, String targetKey);
}
