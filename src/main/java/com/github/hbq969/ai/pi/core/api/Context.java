package com.github.hbq969.ai.pi.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 会话上下文。
 */
public class Context {

    private Optional<String> systemPrompt = Optional.empty();
    private List<Message> messages = new ArrayList<>();
    private List<Tool> tools = new ArrayList<>();

    public Context() {
    }

    public Context(String systemPrompt) {
        this.systemPrompt = Optional.ofNullable(systemPrompt);
    }

    public Optional<String> systemPrompt() {
        return systemPrompt;
    }

    public Context systemPrompt(String prompt) {
        this.systemPrompt = Optional.ofNullable(prompt);
        return this;
    }

    public List<Message> messages() {
        return messages;
    }

    public Context addMessage(Message msg) {
        this.messages.add(msg);
        return this;
    }

    public Context messages(List<Message> msgs) {
        this.messages = new ArrayList<>(msgs);
        return this;
    }

    public List<Tool> tools() {
        return tools;
    }

    public Context tools(List<Tool> tools) {
        this.tools = new ArrayList<>(tools);
        return this;
    }
}
