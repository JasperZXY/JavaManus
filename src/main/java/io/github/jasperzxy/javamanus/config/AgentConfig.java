package io.github.jasperzxy.javamanus.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import io.github.jasperzxy.javamanus.agent.ManusAgent;

/**
 * Agent Bean 配置。
 * ManusAgent 是有状态的，每次请求需要新建实例（prototype 作用域）。
 */
@Configuration
public class AgentConfig {

    @Bean
    @Scope("prototype")
    public ManusAgent manusAgent(ChatModel arkChatModel, JavaManusProperties props) {
        return new ManusAgent(arkChatModel, props);
    }
}
