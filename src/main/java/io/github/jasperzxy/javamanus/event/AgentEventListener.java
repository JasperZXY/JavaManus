package io.github.jasperzxy.javamanus.event;

import java.util.Map;

/**
 * Agent 事件监听器，用于 SSE 等外部观察者接收 Agent 执行过程。
 */
public interface AgentEventListener {

    /** Agent 的思考内容 */
    void onThought(String content);

    /** 调用工具 */
    void onToolCall(String toolName, Map<String, Object> args);

    /** 工具执行结果 */
    void onToolResult(String toolName, String result);

    /** 执行完成 */
    void onComplete(String finalResult);

    /** 执行出错 */
    void onError(Throwable e);
}
