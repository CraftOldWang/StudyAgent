package com.studyagent.ingest.storage;

import com.studyagent.config.ObjectStorageProperties;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

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
     * 初始化 S3 原生 Multipart Upload。
     *
     * <p>这一步只创建上传会话，不写入文件内容。返回的 uploadId 是后续并发 uploadPart
     * 和最终 complete 的关联凭证。</p>
     */
    @Override
    public String initiateMultipartUpload(String objectKey, String contentType) {
        CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .contentType(contentType)
                .build();
        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(request);
        return response.uploadId();
    }

    /**
     * 上传 S3 Multipart Upload 的一个 part。
     */
    @Override
    public MultipartUploadPart uploadPart(
            String objectKey,
            String uploadId,
            int partNumber,
            InputStream inputStream,
            long contentLength
    ) {
        UploadPartRequest request = UploadPartRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .contentLength(contentLength)
                .build();
        UploadPartResponse response = s3Client.uploadPart(request, RequestBody.fromInputStream(inputStream, contentLength));
        return new MultipartUploadPart(partNumber, response.eTag());
    }

    /**
     * 完成 S3 Multipart Upload，由 RustFS/S3 在服务端根据 partNumber 和 eTag 组装最终对象。
     */
    @Override
    public void completeMultipartUpload(String objectKey, String uploadId, List<MultipartUploadPart> parts) {
        List<CompletedPart> completedParts = parts.stream()
                .sorted(Comparator.comparingInt(MultipartUploadPart::partNumber))
                .map(part -> CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(part.eTag())
                        .build())
                .toList();
        CompleteMultipartUploadRequest request = CompleteMultipartUploadRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder()
                        .parts(completedParts)
                        .build())
                .build();
        s3Client.completeMultipartUpload(request);
    }

    /**
     * 中止 S3 Multipart Upload，避免失败测试留下未完成 part 占用对象存储空间。
     */
    @Override
    public void abortMultipartUpload(String objectKey, String uploadId) {
        AbortMultipartUploadRequest request = AbortMultipartUploadRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .uploadId(uploadId)
                .build();
        s3Client.abortMultipartUpload(request);
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
