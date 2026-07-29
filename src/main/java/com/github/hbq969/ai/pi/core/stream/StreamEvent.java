package com.github.hbq969.ai.pi.core.stream;

import com.github.hbq969.ai.pi.core.api.Message;
import com.github.hbq969.ai.pi.core.api.StopReason;

/**
 * 流式事件（sealed interface，对应 TS AssistantMessageEvent）。
 */
public sealed interface StreamEvent {

    /**
     * 获取当前局部消息（所有子类都有 partial 字段）。
     */
    default Message.AssistantMessage partial() {
        if (this instanceof Start s) return s.partial();
        if (this instanceof TextStart s) return s.partial();
        if (this instanceof TextDelta s) return s.partial();
        if (this instanceof TextEnd s) return s.partial();
        if (this instanceof ThinkingStart s) return s.partial();
        if (this instanceof ThinkingDelta s) return s.partial();
        if (this instanceof ThinkingEnd s) return s.partial();
        if (this instanceof ToolCallStart s) return s.partial();
        if (this instanceof ToolCallDelta s) return s.partial();
        if (this instanceof ToolCallEnd s) return s.partial();
        if (this instanceof Done d) return d.message();
        if (this instanceof Error e) return e.partial();
        throw new IllegalStateException("Unknown event type: " + this.getClass().getSimpleName());
    }

    /** 流开始。 */
    record Start(Message.AssistantMessage partial) implements StreamEvent {
    }

    /** 文本块开始。 */
    record TextStart(int contentIndex, Message.AssistantMessage partial) implements StreamEvent {
    }

    /** 文本增量。 */
    record TextDelta(int contentIndex, String delta, Message.AssistantMessage partial) implements StreamEvent {
    }

    /** 文本块结束。 */
    record TextEnd(int contentIndex, String content, Message.AssistantMessage partial) implements StreamEvent {
    }

    /** 思考块开始。 */
    record ThinkingStart(int contentIndex, Message.AssistantMessage partial) implements StreamEvent {
    }

    /** 思考增量。 */
    record ThinkingDelta(int contentIndex, String delta, Message.AssistantMessage partial) implements StreamEvent {
    }

    /** 思考块结束。 */
    record ThinkingEnd(int contentIndex, String content, Message.AssistantMessage partial) implements StreamEvent {
    }

    /** 工具调用开始。 */
    record ToolCallStart(int contentIndex, Message.AssistantMessage partial) implements StreamEvent {
    }

    /** 工具调用增量（JSON 参数片段）。 */
    record ToolCallDelta(int contentIndex, String delta, Message.AssistantMessage partial) implements StreamEvent {
    }

    /** 工具调用结束。 */
    record ToolCallEnd(int contentIndex, Message.ContentBlock.ToolCallBlock toolCall, Message.AssistantMessage partial) implements StreamEvent {
    }

    /** 流正常结束。 */
    record Done(StopReason reason, Message.AssistantMessage message) implements StreamEvent {
    }

    /** 流出错。 */
    record Error(String error, Message.AssistantMessage partial) implements StreamEvent {
    }
}
