package com.studyagent.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch Java API Client 配置。
 */
@Configuration
public class ElasticsearchConfiguration {

    @Bean(destroyMethod = "close")
    ElasticsearchTransport elasticsearchTransport(
            ElasticsearchProperties properties,
            ObjectMapper objectMapper
    ) {
        RestClient restClient = RestClient.builder(HttpHost.create(properties.endpoint())).build();
        return new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
    }

    @Bean
    ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
