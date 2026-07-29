package com.github.hbq969.ai.pi.core.agent;

import com.github.hbq969.ai.pi.core.api.Model;

import java.util.Optional;

/**
 * prepareNextTurn 回调的返回值，允许切换模型。
 * 对应 TS prepareNextTurn 的返回类型。
 */
public record PrepareResult(
        Optional<AgentContext> context,
        Optional<Model> model,
        Optional<String> thinkingLevel) {

    /** 不做任何变更。 */
    public static final PrepareResult NOOP = new PrepareResult(
            Optional.empty(), Optional.empty(), Optional.empty());

    public static PrepareResult withModel(Model m) {
        return new PrepareResult(Optional.empty(), Optional.of(m), Optional.empty());
    }
}
