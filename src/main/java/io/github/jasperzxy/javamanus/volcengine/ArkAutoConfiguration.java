package io.github.jasperzxy.javamanus.volcengine;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

@Configuration
@EnableConfigurationProperties(ArkProperties.class)
@Slf4j
public class ArkAutoConfiguration {

    public ArkAutoConfiguration() {
        log.info("ArkAutoConfiguration init");
    }

    @Bean(name = "arkChatModel")
    public ChatModel chatModel(ArkProperties props) {
        //log.info("ArkAutoConfiguration chatModel {}", JsonUtils.toJson(props));
        return new ArkChatModel(props);
    }

    @Bean(name = "arkEmbeddingModel")
    public EmbeddingModel embeddingModel(ArkProperties props) {
        //log.info("ArkAutoConfiguration embeddingModel {}", JsonUtils.toJson(props));
        return new ArkEmbeddingModel(props);
    }
}
