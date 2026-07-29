package com.github.hbq969.ai.pi.core.agent;

import com.github.hbq969.ai.pi.core.api.Message;
import com.github.hbq969.ai.pi.core.stream.StreamEvent;

import java.util.List;
import java.util.Map;

/**
 * Agent 事件（对应 TS AgentEvent）。
 */
public sealed interface AgentEvent {

    /** Agent 开始，可触发多次。 */
    record AgentStart() implements AgentEvent {
    }

    /** Provider 或 Agent 层失败，但当前 REPL 会话仍可继续。 */
    record AgentFailure(Throwable error) implements AgentEvent {
    }

    /** 一轮对话开始。 */
    record TurnStart() implements AgentEvent {
    }

    /** 消息开始。 */
    record MessageStart(Message message) implements AgentEvent {
    }

    /** 助手消息更新（流式增量）。 */
    record MessageUpdate(StreamEvent event, Message.AssistantMessage partial) implements AgentEvent {
    }

    /** 消息结束。 */
    record MessageEnd(Message message) implements AgentEvent {
    }

    /** 工具执行开始。 */
    record ToolExecutionStart(String toolCallId, String toolName, Map<String, Object> args) implements AgentEvent {
    }

    /** 工具执行结束。 */
    record ToolExecutionEnd(String toolCallId, String toolName, ToolResult result, boolean isError) implements AgentEvent {
    }

    /** 一轮对话结束。 */
    record TurnEnd(Message.AssistantMessage assistantMessage, List<Message.ToolResultMessage> toolResults) implements AgentEvent {
    }

    /** Agent 完成，messages 为本次新增的所有消息。 */
    record AgentEnd(List<Message> messages) implements AgentEvent {
    }
}
