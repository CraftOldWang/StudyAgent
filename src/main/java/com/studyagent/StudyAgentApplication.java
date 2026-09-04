package com.studyagent;

import com.studyagent.config.AiModelProperties;
import com.studyagent.config.CanalProperties;
import com.studyagent.config.ElasticsearchProperties;
import com.studyagent.config.ObjectStorageProperties;
import com.studyagent.config.RagProperties;
import com.studyagent.config.StudyRocketMqProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * StudyAgent Spring Boot 启动类。
 */
@SpringBootApplication
@MapperScan("com.studyagent.mapper")
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
