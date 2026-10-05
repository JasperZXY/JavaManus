package io.github.jasperzxy.javamanus.volcengine;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "spring.ai.volcengine")
public class ArkProperties {

    private String apiKey;
    private String baseUrl;
    private double temperature;
    private String chatModel;
    private String embeddingModel;
    private Integer dimensions;
    private String systemPrompt;
}
