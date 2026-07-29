package com.github.hbq969.ai.pi.core.extension;

import com.github.hbq969.ai.pi.core.agent.AgentTool;

import java.util.Map;

/** Policy hook for authorization, risk checks, approval, and audit before tool execution. */
public interface ExecutionPolicy {

    Decision check(ToolInvocation invocation);

    record ToolInvocation(String sessionId, AgentTool tool,
                          String toolCallId, Map<String, Object> arguments) {
        public ToolInvocation {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, "");
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason == null ? "Denied by execution policy" : reason);
        }
    }
}
