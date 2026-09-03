package com.studyagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.ElasticsearchTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ElasticsearchConfigurationTest {

    @Test
    void createsTypedClientWithoutOpeningRemoteConnection() throws Exception {
        ElasticsearchConfiguration configuration = new ElasticsearchConfiguration();
        ElasticsearchProperties properties = properties();
        ElasticsearchTransport transport = configuration.elasticsearchTransport(properties, new ObjectMapper());

        try {
            ElasticsearchClient client = configuration.elasticsearchClient(transport);
            assertThat(client).isNotNull();
        } finally {
            transport.close();
        }
    }

    private ElasticsearchProperties properties() {
        return new ElasticsearchProperties(
                "http://localhost:9200",
                "chunks-v1",
                "chunks-v1-read",
                "chunks-v1-write",
                1024);
    }
}
