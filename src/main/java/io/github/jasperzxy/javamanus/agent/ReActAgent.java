package io.github.jasperzxy.javamanus.agent;

import org.springframework.ai.chat.model.ChatModel;

/**
 * ReAct 模式 Agent：think（思考）+ act（行动）。
 * 对应 OpenManus 的 ReActAgent。
 */
public abstract class ReActAgent extends BaseAgent {

    protected ReActAgent(ChatModel chatModel, String name, String description) {
        super(chatModel, name, description);
    }

    /**
     * 思考：决定下一步是否需要行动。
     *
     * @return true 表示需要执行 act()
     */
    protected abstract boolean think();

    /**
     * 行动：执行已决定的动作。
     *
     * @return 行动结果
     */
    protected abstract String act();

    @Override
    protected String step() {
        boolean shouldAct = think();
        if (!shouldAct) {
            return "Thinking complete - no action needed";
        }
        return act();
    }
}
