package com.github.hbq969.ai.pi.core.agent;

import com.github.hbq969.ai.pi.core.api.Context;
import com.github.hbq969.ai.pi.core.api.Message;

import java.util.*;

/**
 * Agent 上下文（对应 TS AgentContext）。
 * 在 Context 基础上增加了工具注册表。
 */
public class AgentContext {

    private final String systemPrompt;
    private final List<Message> messages;
    private final Map<String, AgentTool> tools;
    private int deltaStart = 0;

    public AgentContext(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        this.messages = new ArrayList<>();
        this.tools = new LinkedHashMap<>();
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public List<Message> messages() {
        return messages;
    }

    public Map<String, AgentTool> tools() {
        return tools;
    }

    public AgentContext addTool(AgentTool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public AgentContext addMessage(Message msg) {
        messages.add(msg);
        return this;
    }

    public AgentContext addMessages(List<Message> msgs) {
        messages.addAll(msgs);
        return this;
    }

    /**
     * 替换最后一条消息（用于流式更新同一个 AssistantMessage）。
     */
    public void replaceLast(Message msg) {
        if (!messages.isEmpty()) {
            messages.set(messages.size() - 1, msg);
        } else {
            messages.add(msg);
        }
    }

    /**
     * 本次 prompt() 调用新增的消息。
     */
    public List<Message> deltaMessages() {
        if (deltaStart >= messages.size()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(messages.subList(deltaStart, messages.size()));
    }

    /**
     * 重置增量跟踪（准备下一轮调用）。
     */
    public void resetDelta() {
        deltaStart = messages.size();
    }

    /**
     * 转为 LLM 调用用的 Context。
     */
    public Context toContext() {
        return new Context(systemPrompt).messages(new ArrayList<>(messages));
    }

}
