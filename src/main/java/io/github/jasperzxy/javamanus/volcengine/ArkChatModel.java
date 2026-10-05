package io.github.jasperzxy.javamanus.volcengine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionResult;
import com.volcengine.ark.runtime.model.completion.chat.ChatFunction;
import com.volcengine.ark.runtime.model.completion.chat.ChatFunctionCall;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.model.completion.chat.ChatTool;
import com.volcengine.ark.runtime.model.completion.chat.ChatToolCall;
import com.volcengine.ark.runtime.service.ArkService;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;

public class ArkChatModel implements ChatModel {

    private final ArkProperties props;

    private final ArkService service;

    private final ToolCallingManager toolCallingManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ArkChatModel(ArkProperties props) {
        this.props = props;
        this.toolCallingManager = ToolCallingManager.builder().build();
        ConnectionPool connectionPool = new ConnectionPool(5, 1, TimeUnit.SECONDS);
        Dispatcher dispatcher = new Dispatcher();
        service = ArkService.builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .apiKey(props.getApiKey())
                .build();
    }

    public void destroy() {
        service.shutdownExecutor();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return DefaultToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        System.out.println("ark call prompt:" + JsonUtils.toJson(prompt));

        List<ChatMessage> messagesForReqList = toArkMessages(prompt);

        List<ChatTool> tools = extractTools(prompt);

        ChatCompletionRequest.Builder reqBuilder = ChatCompletionRequest.builder()
                .model(props.getChatModel())
                .messages(messagesForReqList);

        if (!tools.isEmpty()) {
            reqBuilder.tools(tools);
        }

        ChatCompletionRequest req = reqBuilder.build();
        var resp = service.createChatCompletion(req);
        ChatResponse chatResponse = toChatResponse(resp);

        if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions
                && ToolCallingChatOptions.isInternalToolExecutionEnabled(toolCallingChatOptions)) {
            boolean hasToolCalls = chatResponse.getResults().stream()
                    .anyMatch(g -> g.getOutput().hasToolCalls());

            if (hasToolCalls) {
                ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, chatResponse);

                if (toolExecutionResult.returnDirect()) {
                    return new ChatResponse(ToolExecutionResult.buildGenerations(toolExecutionResult));
                }

                Prompt newPrompt = new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions());
                return call(newPrompt);
            }
        }

        return chatResponse;
    }

    private List<ChatMessage> toArkMessages(Prompt prompt) {
        List<ChatMessage> messages = new ArrayList<>();

        if (StringUtils.isNoneBlank(props.getSystemPrompt())) {
            messages.add(ChatMessage.builder()
                    .role(ChatMessageRole.SYSTEM)
                    .content(props.getSystemPrompt())
                    .build());
        }

        for (Message message : prompt.getInstructions()) {
            switch (message.getMessageType()) {
                case SYSTEM -> messages.add(ChatMessage.builder()
                        .role(ChatMessageRole.SYSTEM)
                        .content(message.getText())
                        .build());
                case USER -> messages.add(ChatMessage.builder()
                        .role(ChatMessageRole.USER)
                        .content(message.getText())
                        .build());
                case ASSISTANT -> {
                    AssistantMessage assistantMessage = (AssistantMessage) message;
                    ChatMessage.Builder msgBuilder = ChatMessage.builder()
                            .role(ChatMessageRole.ASSISTANT);
                    if (StringUtils.isNotBlank(assistantMessage.getText())) {
                        msgBuilder.content(assistantMessage.getText());
                    }
                    if (assistantMessage.hasToolCalls()) {
                        List<ChatToolCall> toolCalls = assistantMessage.getToolCalls().stream()
                                .map(tc -> new ChatToolCall(tc.id(), "function",
                                        new ChatFunctionCall(tc.name(), tc.arguments())))
                                .toList();
                        msgBuilder.toolCalls(toolCalls);
                    }
                    messages.add(msgBuilder.build());
                }
                case TOOL -> {
                    ToolResponseMessage toolResponseMessage = (ToolResponseMessage) message;
                    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                        messages.add(ChatMessage.builder()
                                .role(ChatMessageRole.TOOL)
                                .content(response.responseData())
                                .toolCallId(response.id())
                                .build());
                    }
                }
            }
        }

        return messages;
    }

    private List<ChatTool> extractTools(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions) {
            List<ToolCallback> toolCallbacks = toolCallingChatOptions.getToolCallbacks();
            if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
                return toolCallbacks.stream()
                        .map(tc -> toChatTool(tc.getToolDefinition()))
                        .toList();
            }
        }
        return List.of();
    }

    private ChatTool toChatTool(ToolDefinition toolDefinition) {
        try {
            JsonNode parameters = objectMapper.readTree(toolDefinition.inputSchema());
            ChatFunction function = new ChatFunction.Builder()
                    .name(toolDefinition.name())
                    .description(toolDefinition.description())
                    .parameters(parameters)
                    .build();
            return new ChatTool("function", function);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert tool definition: " + toolDefinition.name(), e);
        }
    }

    private ChatResponse toChatResponse(ChatCompletionResult response) {
        ChatMessage message = response.getChoices().get(0).getMessage();

        List<AssistantMessage.ToolCall> toolCalls = List.of();
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            toolCalls = message.getToolCalls().stream()
                    .map(tc -> new AssistantMessage.ToolCall(
                            tc.getId(),
                            tc.getType() != null ? tc.getType() : "function",
                            tc.getFunction() != null ? tc.getFunction().getName() : "",
                            tc.getFunction() != null ? tc.getFunction().getArguments() : ""))
                    .toList();
        }

        String content = message.getContent() != null ? message.getContent().toString() : "";
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content(content)
                .toolCalls(toolCalls)
                .build();
        Generation generation = new Generation(assistantMessage);

        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .id(response.getId())
                .model(response.getModel())
                .build();

        return new ChatResponse(List.of(generation), metadata);
    }

}
