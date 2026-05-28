package com.studyagent.infrastructure.objectstorage;

import com.studyagent.common.config.ObjectStorageProperties;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3 兼容对象存储适配器，可对接 MinIO、RustFS 等实现。
 */
@Service
@RequiredArgsConstructor
public class S3ObjectStorageService implements ObjectStorageService {

    private final S3Client s3Client;
    private final ObjectStorageProperties properties;

    /**
     * 应用启动时创建或校验 bucket。
     */
    @PostConstruct
    public void init() {
        ensureBucket();
    }

    /**
     * bucket 不存在时创建，其他 S3 异常向上抛出。
     */
    @Override
    public void ensureBucket() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
        } catch (NoSuchBucketException ex) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
        }
    }

    /**
     * 流式写入对象，避免大文件进入内存。
     */
    @Override
    public void putObject(String objectKey, InputStream inputStream, long contentLength, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .contentType(contentType)
                .build();
        s3Client.putObject(request, RequestBody.fromInputStream(inputStream, contentLength));
    }

    /**
     * 获取对象输入流，调用方负责关闭返回的流。
     */
    @Override
    public InputStream getObject(String objectKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .build();
        ResponseInputStream<?> response = s3Client.getObject(request);
        return response;
    }

    /**
     * 同 bucket 内复制对象。
     */
    @Override
    public void copyObject(String sourceKey, String targetKey) {
        CopyObjectRequest request = CopyObjectRequest.builder()
                .sourceBucket(properties.bucket())
                .sourceKey(sourceKey)
                .destinationBucket(properties.bucket())
                .destinationKey(targetKey)
                .build();
        s3Client.copyObject(request);
    }
}
