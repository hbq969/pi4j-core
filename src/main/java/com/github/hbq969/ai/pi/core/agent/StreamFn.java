package com.github.hbq969.ai.pi.core.agent;

import com.github.hbq969.ai.pi.core.api.Context;
import com.github.hbq969.ai.pi.core.api.Message;
import com.github.hbq969.ai.pi.core.api.Model;
import com.github.hbq969.ai.pi.core.stream.PiEventStream;
import com.github.hbq969.ai.pi.core.stream.StreamEvent;

/**
 * 流式 LLM 调用函数注入点，对应 TS StreamFn。
 * 解耦 AgentLoop 和具体的 Provider 调用。
 */
@FunctionalInterface
public interface StreamFn {

    /**
     * 流式调用 LLM。
     *
     * @param model  模型配置（含 maxTokens）
     * @param ctx    会话上下文（含 tools）
     * @param apiKey API key
     * @return 流式事件流
     */
    PiEventStream<StreamEvent, Message.AssistantMessage> stream(
            Model model, Context ctx, String apiKey);
}
