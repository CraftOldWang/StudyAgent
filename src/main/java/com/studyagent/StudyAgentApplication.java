package com.studyagent;

import com.studyagent.common.config.ElasticsearchProperties;
import com.studyagent.common.config.ObjectStorageProperties;
import com.studyagent.common.config.RagProperties;
import com.studyagent.common.config.StudyRocketMqProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan({
        "com.studyagent.modules.storage.infrastructure",
        "com.studyagent.modules.knowledge.infrastructure"
})
@EnableConfigurationProperties({
        ObjectStorageProperties.class,
        ElasticsearchProperties.class,
        RagProperties.class,
        StudyRocketMqProperties.class
})
public class StudyAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudyAgentApplication.class, args);
    }
}
