package com.github.hbq969.ai.pi.core.agent;

import java.util.Map;

/**
 * 工具执行结果（对应 TS AgentToolResult）。
 *
 * @param output  给 LLM 的文本输出（写入 ToolResultMessage.content）
 * @param details 给 UI 或日志的附加数据
 */
public record ToolResult(String output, Map<String, Object> details) {
}
