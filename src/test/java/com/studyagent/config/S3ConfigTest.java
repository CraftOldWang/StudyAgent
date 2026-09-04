package com.studyagent.config;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

class S3ConfigTest {

    @Test
    void acceptsHttpEndpointForS3CompatibleStorage() {
        ObjectStorageProperties properties = new ObjectStorageProperties(
                "http://localhost:9000",
                "access",
                "secret",
                "study-agent",
                "us-east-1",
                true);

        assertThatCode(() -> {
            try (S3Client client = new S3Config().s3Client(properties)) {
            }
        }).doesNotThrowAnyException();
    }
}
