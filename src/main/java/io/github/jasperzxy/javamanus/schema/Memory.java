package io.github.jasperzxy.javamanus.schema;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.Message;

/**
 * Agent 记忆：消息列表，带上限保护。
 * 对应 OpenManus 的 Memory 类。
 */
public class Memory {

    private List<Message> messages = new ArrayList<>();
    private int maxMessages;

    public Memory() {
        this(100);
    }

    public Memory(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public synchronized void addMessage(Message message) {
        messages.add(message);
        trimIfNeeded();
    }

    public synchronized void addMessages(List<Message> msgs) {
        messages.addAll(msgs);
        trimIfNeeded();
    }

    private void trimIfNeeded() {
        if (messages.size() > maxMessages) {
            messages = new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));
        }
    }

    public synchronized void clear() {
        messages.clear();
    }

    public synchronized List<Message> getMessages() {
        return new ArrayList<>(messages);
    }

    public synchronized int size() {
        return messages.size();
    }

    public synchronized Message getLast() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }
}
