package com.github.hbq969.ai.pi.core.api;

import java.util.List;
import java.util.Map;

/**
 * 消息类型系统。
 */
public sealed interface Message permits Message.UserMessage, Message.AssistantMessage, Message.ToolResultMessage {

    Role role();

    /** 用户消息。 */
    record UserMessage(String content) implements Message {
        @Override
        public Role role() {
            return Role.USER;
        }
    }

    /** 助手消息（含文本、思考、工具调用等混合内容块）。 */
    record AssistantMessage(
            List<ContentBlock> content,
            String api,
            String provider,
            String model,
            Usage usage,
            StopReason stopReason,
            String error) implements Message {

        @Override
        public Role role() {
            return Role.ASSISTANT;
        }
    }

    /** 工具执行结果消息。 */
    record ToolResultMessage(
            String toolCallId,
            String toolName,
            String output,
            boolean isError) implements Message {

        @Override
        public Role role() {
            return Role.TOOL_RESULT;
        }
    }

    /**
     * 助手消息中的内容块。
     */
    sealed interface ContentBlock {

        /** 文本内容。 */
        record TextBlock(String text, String textSignature) implements ContentBlock {
            public TextBlock(String text) {
                this(text, null);
            }
        }

        /** 思考内容（推理过程）。 */
        record ThinkingBlock(String thinking, String thinkingSignature) implements ContentBlock {
        }

        /** 工具调用。 */
        record ToolCallBlock(String id, String name, Map<String, Object> arguments) implements ContentBlock {
        }
    }
}
