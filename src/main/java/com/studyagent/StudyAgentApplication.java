package com.studyagent;

import com.studyagent.common.config.AiModelProperties;
import com.studyagent.common.config.CanalProperties;
import com.studyagent.common.config.ElasticsearchProperties;
import com.studyagent.common.config.ObjectStorageProperties;
import com.studyagent.common.config.RagProperties;
import com.studyagent.common.config.StudyRocketMqProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * StudyAgent Spring Boot 启动类。
 */
@SpringBootApplication
@MapperScan({
        "com.studyagent.modules.storage.infrastructure",
        "com.studyagent.modules.knowledge.infrastructure",
        "com.studyagent.modules.learning.infrastructure",
        "com.studyagent.modules.tool.infrastructure",
        "com.studyagent.modules.review.infrastructure"
})
@EnableConfigurationProperties({
        AiModelProperties.class,
        ObjectStorageProperties.class,
        ElasticsearchProperties.class,
        RagProperties.class,
        StudyRocketMqProperties.class,
        CanalProperties.class
})
public class StudyAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudyAgentApplication.class, args);
    }
}
