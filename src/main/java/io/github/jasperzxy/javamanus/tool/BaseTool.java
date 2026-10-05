package io.github.jasperzxy.javamanus.tool;

import java.util.HashMap;
import java.util.Map;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.jasperzxy.javamanus.exception.ToolError;

/**
 * 工具基类，同时实现 Spring AI 的 {@link ToolCallback}，
 * 既能被 ChatModel 识别为工具定义，又能由 Agent 手动调用 {@link #execute(Map)}。
 */
public abstract class BaseTool implements ToolCallback {

    protected final String name;
    protected final String description;
    protected final Map<String, Object> parameters;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected BaseTool(String name, String description, Map<String, Object> parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    /**
     * 执行工具，由子类实现。
     *
     * @param args 解析后的参数
     * @return 工具输出文本
     * @throws ToolError 工具执行失败
     */
    public abstract String execute(Map<String, Object> args);

    @Override
    public String call(String functionArguments) {
        try {
            Map<String, Object> args = parseArgs(functionArguments);
            return execute(args);
        } catch (ToolError e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static Map<String, Object> parseArgs(String functionArguments) {
        if (functionArguments == null || functionArguments.isBlank()) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(functionArguments, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(toJsonSchema(parameters))
                .build();
    }

    private static String toJsonSchema(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        try {
            return MAPPER.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new ToolError("Failed to serialize tool parameters", e);
        }
    }
}
