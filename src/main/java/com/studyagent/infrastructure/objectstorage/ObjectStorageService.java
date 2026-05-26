package com.studyagent.infrastructure.objectstorage;

import java.io.InputStream;

public interface ObjectStorageService {
    void ensureBucket();

    void putObject(String objectKey, InputStream inputStream, long contentLength, String contentType);

    InputStream getObject(String objectKey);

    void copyObject(String sourceKey, String targetKey);
}
