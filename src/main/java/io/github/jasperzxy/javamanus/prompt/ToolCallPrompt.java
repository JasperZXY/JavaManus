package io.github.jasperzxy.javamanus.prompt;

/**
 * ToolCall Agent 提示词。
 * 对应 OpenManus 的 app/prompt/toolcall.py。
 */
public final class ToolCallPrompt {

    private ToolCallPrompt() {
    }

    public static final String SYSTEM_PROMPT = "You are an agent that can execute tool calls";

    public static final String NEXT_STEP_PROMPT =
            "If you want to stop interaction, use `terminate` tool/function call.";
}
