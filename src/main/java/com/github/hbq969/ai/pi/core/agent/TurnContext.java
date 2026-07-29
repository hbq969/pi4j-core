package com.github.hbq969.ai.pi.core.agent;

import com.github.hbq969.ai.pi.core.api.Message;

import java.util.List;

/**
 * 一轮对话结束时的上下文快照，传递给 AgentLoopConfig 回调。
 * 对应 TS prepareNextTurn / shouldStopAfterTurn 的参数。
 */
public record TurnContext(
        Message.AssistantMessage message,
        List<Message.ToolResultMessage> toolResults,
        AgentContext context,
        List<Message> newMessages) {
}
