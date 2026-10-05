package io.github.jasperzxy.javamanus.event;

/**
 * Agent 事件类型
 */
public enum AgentEventType {
    /** Agent 思考内容 */
    THOUGHT,
    /** 工具调用 */
    TOOL_CALL,
    /** 工具执行结果 */
    TOOL_RESULT,
    /** 执行完成 */
    COMPLETE,
    /** 执行出错 */
    ERROR
}
