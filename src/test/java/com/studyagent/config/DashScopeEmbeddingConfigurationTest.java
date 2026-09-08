package com.studyagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DashScopeEmbeddingConfigurationTest {

    @Test
    void preservesWorkspaceHostWhileSelectingNativeSdkRoute() {
        assertThat(DashScopeEmbeddingConfiguration.nativeBaseUrl(
                "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/"))
                .isEqualTo("https://workspace.cn-beijing.maas.aliyuncs.com/api/v1");
    }

    @Test
    void preservesExplicitNativeEndpointAndRejectsMissingEndpoint() {
        assertThat(DashScopeEmbeddingConfiguration.nativeBaseUrl("https://dashscope.aliyuncs.com/api/v1"))
                .isEqualTo("https://dashscope.aliyuncs.com/api/v1");
        assertThatThrownBy(() -> DashScopeEmbeddingConfiguration.nativeBaseUrl(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
