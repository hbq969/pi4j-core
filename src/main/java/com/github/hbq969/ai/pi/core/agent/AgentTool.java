package com.github.hbq969.ai.pi.core.agent;

import com.github.hbq969.ai.pi.core.api.Tool;

import java.util.Map;

/**
 * 可执行工具（对应 TS AgentTool）。
 * 继承 Tool，增加 execute 方法。
 */
public interface AgentTool extends Tool {

    /** UI 显示标签。 */
    String label();

    /**
     * 执行工具。
     *
     * @param toolCallId LLM 生成的调用 ID
     * @param params     校验后的参数
     * @return 执行结果
     * @throws Exception 执行异常（会被捕获并包装为错误 ToolResultMessage）
     */
    ToolResult execute(String toolCallId, Map<String, Object> params) throws Exception;
}
