package com.github.hbq969.ai.pi.core.extension;

import com.github.hbq969.ai.pi.core.agent.AgentTool;

/**
 * 可扩展工具契约——Agent 运行时可注册、可发现、可执行。
 * 继承 {@link AgentTool}，增加来源和风险元数据。
 */
public interface ToolExtension extends AgentTool {

    /** 工具来源标识：builtin / mcp / custom */
    default String source() {
        return "builtin";
    }

    /** 工具风险等级 */
    default ToolRisk risk() {
        return ToolRisk.READ_ONLY;
    }
}
