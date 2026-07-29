package com.github.hbq969.ai.pi.core.api;

/**
 * 工具定义。给 LLM 的 jsonSchema 直接使用 JSON Schema 字符串。
 */
public interface Tool {

    /** 工具名称。 */
    String name();

    /** 工具描述。 */
    String description();

    /** JSON Schema 字符串，传给 LLM 定义参数格式。 */
    String jsonSchema();
}
