package io.github.jasperzxy.javamanus.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import io.github.jasperzxy.javamanus.event.AgentEventListener;
import io.github.jasperzxy.javamanus.schema.AgentState;
import io.github.jasperzxy.javamanus.schema.Memory;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 抽象基类，提供状态管理、记忆、步数循环和卡死检测。
 * 对应 OpenManus 的 BaseAgent。
 */
@Slf4j
public abstract class BaseAgent {

    protected final ChatModel chatModel;
    protected final String name;
    protected final String description;

    protected String systemPrompt;
    protected String nextStepPrompt;

    protected final Memory memory = new Memory();
    protected volatile AgentState state = AgentState.IDLE;

    protected int maxSteps = 10;
    protected int currentStep = 0;
    protected int duplicateThreshold = 2;
    protected int maxObserve = 0;

    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();

    protected BaseAgent(ChatModel chatModel, String name, String description) {
        this.chatModel = chatModel;
        this.name = name;
        this.description = description;
    }

    public void addListener(AgentEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(AgentEventListener listener) {
        listeners.remove(listener);
    }

    protected void fireThought(String content) {
        for (AgentEventListener l : listeners) {
            try {
                l.onThought(content);
            } catch (Exception e) {
                log.warn("Listener onThought error", e);
            }
        }
    }

    protected void fireToolCall(String toolName, java.util.Map<String, Object> args) {
        for (AgentEventListener l : listeners) {
            try {
                l.onToolCall(toolName, args);
            } catch (Exception e) {
                log.warn("Listener onToolCall error", e);
            }
        }
    }

    protected void fireToolResult(String toolName, String result) {
        for (AgentEventListener l : listeners) {
            try {
                l.onToolResult(toolName, result);
            } catch (Exception e) {
                log.warn("Listener onToolResult error", e);
            }
        }
    }

    protected void fireComplete(String finalResult) {
        for (AgentEventListener l : listeners) {
            try {
                l.onComplete(finalResult);
            } catch (Exception e) {
                log.warn("Listener onComplete error", e);
            }
        }
    }

    protected void fireError(Throwable e) {
        for (AgentEventListener l : listeners) {
            try {
                l.onError(e);
            } catch (Exception ex) {
                log.warn("Listener onError error", ex);
            }
        }
    }

    /**
     * 执行 Agent 主循环。
     */
    public String run(String request) {
        if (state != AgentState.IDLE) {
            throw new IllegalStateException("Cannot run agent from state: " + state);
        }

        if (request != null && !request.isBlank()) {
            memory.addMessage(new UserMessage(request));
        }

        List<String> results = new ArrayList<>();
        state = AgentState.RUNNING;
        try {
            while (currentStep < maxSteps && state != AgentState.FINISHED) {
                currentStep++;
                log.info("Executing step {}/{}", currentStep, maxSteps);
                String stepResult;
                try {
                    stepResult = step();
                } catch (Exception e) {
                    log.error("Step {} failed", currentStep, e);
                    fireError(e);
                    stepResult = "Step " + currentStep + " error: " + e.getMessage();
                }

                if (isStuck()) {
                    handleStuckState();
                }

                results.add("Step " + currentStep + ": " + stepResult);
            }

            if (currentStep >= maxSteps && state != AgentState.FINISHED) {
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
        } finally {
            state = AgentState.IDLE;
            currentStep = 0;
            memory.clear();
        }

        String finalResult = String.join("\n", results);
        fireComplete(finalResult);
        return finalResult;
    }

    /**
     * 执行单步，由子类实现。
     */
    protected abstract String step();

    /**
     * 检测是否卡死（连续重复的 assistant 响应）。
     * 仅统计与最后一条 assistant 消息连续相同的次数，避免将历史中的偶发重复误判为卡死。
     */
    protected boolean isStuck() {
        List<Message> msgs = memory.getMessages();
        if (msgs.size() < 2) {
            return false;
        }
        // 找到最后一条 assistant 消息
        int lastAssistantIdx = -1;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getMessageType() == MessageType.ASSISTANT) {
                lastAssistantIdx = i;
                break;
            }
        }
        if (lastAssistantIdx < 0) {
            return false;
        }
        Message last = msgs.get(lastAssistantIdx);
        if (last.getText() == null || last.getText().isBlank()) {
            return false;
        }
        // 向前统计连续相同的 assistant 消息数量
        int duplicateCount = 0;
        for (int i = lastAssistantIdx - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m.getMessageType() != MessageType.ASSISTANT) {
                continue;
            }
            if (last.getText().equals(m.getText())) {
                duplicateCount++;
            } else {
                break;
            }
        }
        return duplicateCount >= duplicateThreshold;
    }

    protected void handleStuckState() {
        String stuckPrompt = "Observed duplicate responses. Consider new strategies and avoid repeating ineffective paths already attempted.";
        nextStepPrompt = stuckPrompt + "\n" + (nextStepPrompt != null ? nextStepPrompt : "");
        log.warn("Agent detected stuck state. Added prompt: {}", stuckPrompt);
    }

    public AgentState getState() {
        return state;
    }

    public Memory getMemory() {
        return memory;
    }
}
