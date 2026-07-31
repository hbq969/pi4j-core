package com.github.hbq969.ai.pi.core.api;

/**
 * 模型配置。输入类型用枚举位掩码控制。
 */
public record Model(
        String id,
        String name,
        Api api,
        String provider,
        String baseUrl,
        boolean reasoning,
        int contextWindow,
        int maxTokens,
        Cost cost,
        String params) {

    /**
     * 兼容未配置请求参数的旧构造方式。
     */
    public Model(String id, String name, Api api, String provider, String baseUrl,
                 boolean reasoning, int contextWindow, int maxTokens, Cost cost) {
        this(id, name, api, provider, baseUrl, reasoning, contextWindow, maxTokens, cost, null);
    }

    /**
     * 费用（每百万 token 的美元价格）。
     */
    public record Cost(
            double input,      // 输入 $/million tokens
            double output,     // 输出 $/million tokens
            double cacheRead,  // 缓存读取 $/million tokens
            double cacheWrite  // 缓存写入 $/million tokens
    ) {
        public static final Cost FREE = new Cost(0, 0, 0, 0);
    }
}
