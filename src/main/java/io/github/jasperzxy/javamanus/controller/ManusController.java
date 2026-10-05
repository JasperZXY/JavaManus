package io.github.jasperzxy.javamanus.controller;

import java.io.IOException;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.github.jasperzxy.javamanus.agent.ManusAgent;
import io.github.jasperzxy.javamanus.config.JavaManusProperties;
import io.github.jasperzxy.javamanus.event.AgentEventListener;
import lombok.extern.slf4j.Slf4j;

/**
 * Manus Agent SSE 调试接口。
 * <p>
 * POST /api/manus/chat
 * body: { "prompt": "..." }
 * <p>
 * SSE 事件：
 * - thought: Agent 思考内容
 * - tool_call: 调用的工具 {name, args}
 * - tool_result: 工具执行结果 {name, result}
 * - complete: 最终结果
 * - error: 错误信息
 */
@Slf4j
@RestController
@RequestMapping("/api/manus")
public class ManusController {

    private final ObjectProvider<ManusAgent> manusAgentProvider;
    private final JavaManusProperties props;

    public ManusController(ObjectProvider<ManusAgent> manusAgentProvider, JavaManusProperties props) {
        this.manusAgentProvider = manusAgentProvider;
        this.props = props;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        long sseTimeout = props.getSseTimeoutSeconds() * 1000L;
        SseEmitter emitter = new SseEmitter(sseTimeout);

        String prompt = request != null ? request.getPrompt() : null;
        if (prompt == null || prompt.isBlank()) {
            try {
                emitter.send(SseEmitter.event().name("error").data("Parameter 'prompt' is required"));
            } catch (IOException ignored) {
            }
            emitter.complete();
            return emitter;
        }

        ManusAgent agent = manusAgentProvider.getObject();

        AgentEventListener listener = new AgentEventListener() {
            @Override
            public void onThought(String content) {
                send(emitter, "thought", content);
            }

            @Override
            public void onToolCall(String toolName, Map<String, Object> args) {
                send(emitter, "tool_call", Map.of("name", toolName, "args", args));
            }

            @Override
            public void onToolResult(String toolName, String result) {
                send(emitter, "tool_result", Map.of("name", toolName, "result", result));
            }

            @Override
            public void onComplete(String finalResult) {
                send(emitter, "complete", finalResult);
                emitter.complete();
            }

            @Override
            public void onError(Throwable e) {
                send(emitter, "error", e.getMessage());
            }
        };
        agent.addListener(listener);

        // 虚拟线程执行 Agent，避免阻塞 Web 容器线程
        Thread.ofVirtual().start(() -> {
            try {
                agent.run(prompt);
            } catch (Exception e) {
                log.error("Agent execution failed", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.warn("Failed to send SSE event {}: {}", eventName, e.getMessage());
        }
    }
}
