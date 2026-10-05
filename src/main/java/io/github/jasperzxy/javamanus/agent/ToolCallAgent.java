package io.github.jasperzxy.javamanus.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.jasperzxy.javamanus.exception.ToolError;
import io.github.jasperzxy.javamanus.schema.AgentState;
import io.github.jasperzxy.javamanus.schema.ToolChoice;
import io.github.jasperzxy.javamanus.tool.ToolCollection;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具调用 Agent，负责编排 LLM 工具调用循环。
 * 对应 OpenManus 的 ToolCallAgent。
 */
@Slf4j
public abstract class ToolCallAgent extends ReActAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected ToolCollection availableTools;
    protected ToolChoice toolChoice = ToolChoice.AUTO;
    protected List<String> specialToolNames = new ArrayList<>();

    protected List<AssistantMessage.ToolCall> toolCalls = List.of();

    protected ToolCallAgent(ChatModel chatModel, String name, String description) {
        super(chatModel, name, description);
    }

    @Override
    protected boolean think() {
        // 组装消息
        List<Message> messages = buildMessages();

        // 构建工具调用选项（禁用内部自动执行）
        ToolCallingChatOptions options = DefaultToolCallingChatOptions.builder()
                .toolCallbacks(availableTools.getToolCallbacks())
                .internalToolExecutionEnabled(false)
                .build();

        Prompt prompt = new Prompt(messages, options);

        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (Exception e) {
            log.error("LLM call failed", e);
            fireError(e);
            memory.addMessage(new AssistantMessage("Error: " + e.getMessage()));
            // LLM 调用失败，终止循环，避免无效重试浪费配额
            state = AgentState.FINISHED;
            return false;
        }

        AssistantMessage assistantMessage = response.getResult().getOutput();
        String content = assistantMessage.getText();
        toolCalls = assistantMessage.getToolCalls() != null
                ? assistantMessage.getToolCalls()
                : List.of();

        log.info("{} thoughts: {}", name, content);
        log.info("{} selected {} tools", name, toolCalls.size());

        // 记录 assistant 消息到 memory
        memory.addMessage(assistantMessage);
        fireThought(content);

        // 根据 tool_choice 决定是否需要 act
        if (toolChoice == ToolChoice.NONE) {
            return content != null && !content.isBlank();
        }
        if (toolChoice == ToolChoice.REQUIRED && toolCalls.isEmpty()) {
            // 模型未按要求调用工具，注入提示后终止本步，由下一轮重试
            nextStepPrompt = "You are required to call a tool. Please select and call an appropriate tool.";
            log.warn("{} tool_choice=REQUIRED but no tool calls returned, injecting prompt", name);
            return false;
        }
        if (toolChoice == ToolChoice.AUTO && toolCalls.isEmpty()) {
            return content != null && !content.isBlank();
        }
        return !toolCalls.isEmpty();
    }

    @Override
    protected String act() {
        if (toolCalls == null || toolCalls.isEmpty()) {
            Message last = memory.getLast();
            return last != null ? last.getText() : "No content or commands to execute";
        }

        List<String> results = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : toolCalls) {
            String toolName = tc.name();
            // 解析参数仅用于日志和事件，实际执行时把原始 JSON 字符串交给 ToolCallback
            Map<String, Object> args = parseArgs(tc.arguments());

            log.info("Activating tool: {} with args: {}", toolName, args);
            fireToolCall(toolName, args);

            String result;
            try {
                // 直接传 LLM 返回的 JSON 参数字符串，由 Spring AI 的 ToolCallback 负责参数绑定
                result = availableTools.execute(toolName, tc.arguments());
            } catch (Exception e) {
                log.error("Tool {} execution failed", toolName, e);
                result = "Error: " + e.getMessage();
            }

            // 截断超长输出
            if (maxObserve > 0 && result.length() > maxObserve) {
                result = result.substring(0, maxObserve);
            }

            log.info("Tool {} completed. Result: {}", toolName, result);
            fireToolResult(toolName, result);

            // 写入 tool response 到 memory
            ToolResponseMessage.ToolResponse toolResponse =
                    new ToolResponseMessage.ToolResponse(tc.id(), toolName, result);
            memory.addMessage(ToolResponseMessage.builder()
                    .responses(List.of(toolResponse))
                    .build());

            // 特殊工具处理
            if (isSpecialTool(toolName)) {
                log.info("Special tool {} completed the task!", toolName);
                state = AgentState.FINISHED;
            }

            results.add(result);
        }

        return String.join("\n\n", results);
    }

    /**
     * 组装发送给 LLM 的消息列表（system + nextStepPrompt + memory）。
     */
    private List<Message> buildMessages() {
        List<Message> messages = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        if (nextStepPrompt != null && !nextStepPrompt.isBlank()) {
            messages.add(new UserMessage(nextStepPrompt));
            nextStepPrompt = null;
        }
        messages.addAll(memory.getMessages());

        return messages;
    }

    private Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(arguments, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.error("Failed to parse tool arguments: {}", arguments, e);
            return new HashMap<>();
        }
    }

    private boolean isSpecialTool(String toolName) {
        return specialToolNames.stream()
                .anyMatch(n -> n.equalsIgnoreCase(toolName));
    }
}
