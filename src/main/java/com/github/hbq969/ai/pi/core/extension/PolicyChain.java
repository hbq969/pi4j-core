package com.github.hbq969.ai.pi.core.extension;

import com.github.hbq969.ai.pi.core.agent.AgentTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Three-state tool policy chain: allow, confirmation required, or deny. */
public final class PolicyChain {

    private final List<Policy> policies = new ArrayList<>();

    public synchronized void add(Policy policy) {
        policies.add(policy);
    }

    public synchronized Decision evaluate(String sessionId, AgentTool tool,
                                          String toolCallId, Map<String, Object> arguments) {
        Decision result = Decision.allow();
        for (Policy policy : policies) {
            Decision current = policy.evaluate(new Invocation(sessionId, tool, toolCallId, arguments));
            if (current.state() == State.DENY) {
                return current;
            }
            if (current.state() == State.CONFIRM) {
                result = current;
            }
        }
        return result;
    }

    @FunctionalInterface
    public interface Policy {
        Decision evaluate(Invocation invocation);
    }

    public record Invocation(String sessionId, AgentTool tool, String toolCallId,
                             Map<String, Object> arguments) {
        public Invocation {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    public record Decision(State state, String reason) {
        public static Decision allow() { return new Decision(State.ALLOW, ""); }
        public static Decision confirm(String reason) { return new Decision(State.CONFIRM, reason); }
        public static Decision deny(String reason) { return new Decision(State.DENY, reason); }
    }

    public enum State { ALLOW, CONFIRM, DENY }
}
