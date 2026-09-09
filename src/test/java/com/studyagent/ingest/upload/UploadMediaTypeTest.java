package com.studyagent.ingest.upload;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class UploadMediaTypeTest {
    @Test void acceptsBrowserAudioAliasesButRejectsInconsistentMediaTypes() {
        assertThatCode(() -> UploadFileSupport.validateType("lecture.m4a", "audio/x-m4a")).doesNotThrowAnyException();
        assertThatCode(() -> UploadFileSupport.validateType("lecture.wav", "audio/x-wav")).doesNotThrowAnyException();
        assertThatCode(() -> UploadFileSupport.validateType("lecture.MP4", "video/mp4")).doesNotThrowAnyException();
        assertThatThrownBy(() -> UploadFileSupport.validateType("lecture.mp4", "application/pdf")).hasMessageContaining("不匹配");
    }
}
