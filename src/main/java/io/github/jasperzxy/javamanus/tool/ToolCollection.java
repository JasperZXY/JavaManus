package io.github.jasperzxy.javamanus.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import io.github.jasperzxy.javamanus.exception.ToolError;

/**
 * 工具集合，管理多个工具并提供调度能力。
 * 对应 OpenManus 的 ToolCollection。
 * <p>
 * 工具类使用 Spring AI 的 {@code @Tool} 注解定义方法，通过 {@link ToolCallbacks#fromToolObjects}
 * 自动生成 {@link ToolCallback}（含 ToolDefinition + 参数绑定 + 异常包装）。
 */
public class ToolCollection {

    private final ToolCallback[] callbacks;
    private final Map<String, ToolCallback> callbackMap = new LinkedHashMap<>();

    public ToolCollection(Object... toolObjects) {
        this.callbacks = ToolCallbacks.from(toolObjects);
        for (ToolCallback cb : this.callbacks) {
            callbackMap.put(cb.getToolDefinition().name(), cb);
        }
    }

    /**
     * 获取所有 ToolCallback，用于传给 ChatModel 的 toolCallbacks 选项。
     */
    public ToolCallback[] getToolCallbacks() {
        return callbacks;
    }

    /**
     * 按名称查找 ToolCallback。
     */
    public ToolCallback getCallback(String name) {
        return callbackMap.get(name);
    }

    public boolean contains(String name) {
        return callbackMap.containsKey(name);
    }

    /**
     * 执行指定工具。
     *
     * @param name              工具名
     * @param functionArguments LLM 返回的 JSON 参数字符串
     * @return 工具输出文本（异常会被 Spring AI 的 ToolCallback 包装为 "Error: ..."）
     */
    public String execute(String name, String functionArguments) {
        ToolCallback cb = callbackMap.get(name);
        if (cb == null) {
            throw new ToolError("Unknown tool: " + name);
        }
        return cb.call(functionArguments);
    }
}
