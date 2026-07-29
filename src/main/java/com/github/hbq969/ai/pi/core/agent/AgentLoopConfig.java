package com.github.hbq969.ai.pi.core.agent;

import com.github.hbq969.ai.pi.core.api.Message;

import java.util.Collections;
import java.util.List;

/**
 * Agent 循环配置回调，对应 TS AgentLoopConfig。
 * 所有方法均有默认空实现，按需覆盖。
 */
public interface AgentLoopConfig {

    /**
     * 每轮结束后回调，可切换模型或修改上下文。
     * 返回 PrepareResult.NOOP 表示不做变更。
     */
    default PrepareResult prepareNextTurn(TurnContext ctx) {
        return PrepareResult.NOOP;
    }

    /**
     * 每轮结束后检查是否应提前终止 Agent。
     * 返回 true 则停止。
     */
    default boolean shouldStopAfterTurn(TurnContext ctx) {
        return false;
    }

    /**
     * 获取用户中途追加的消息（steering messages）。
     * 在下一轮 LLM 调用前注入到上下文。
     */
    default List<Message> getSteeringMessages() {
        return Collections.emptyList();
    }

    /**
     * Agent 内部循环结束后，获取追问消息（follow-up messages）。
     * 非空则重新进入 inner 循环处理。
     */
    default List<Message> getFollowUpMessages() {
        return Collections.emptyList();
    }
}
