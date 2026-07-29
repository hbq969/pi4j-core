package com.github.hbq969.ai.pi.core.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * pi4j-core 配置，集中管理所有可配置值。
 * 对应 Spring 项目的 {@code @ConfigurationProperties}。
 */
public class PiCoreConfig {

    /** Agent 循环最大对话轮数，默认 20 */
    private int maxTurns = 20;

    /** HTTP 连接超时（秒），默认 30 */
    private long connectTimeoutSeconds = 30;

    /** HTTP 读取超时（秒），默认 300（5 分钟流式响应） */
    private long readTimeoutSeconds = 300;

    /** HTTP 写入超时（秒），默认 30 */
    private long writeTimeoutSeconds = 30;

    /** HTTP 请求总超时（毫秒），默认 600000（10 分钟） */
    private long timeoutMs = 600_000;

    /** 最大重试次数，默认 2 */
    private int maxRetries = 2;

    /** 最大重试延迟（毫秒），超过此值直接失败，默认 60000（60 秒） */
    private long maxRetryDelayMs = 60_000;

    /** 传输协议：sse / websocket / auto，默认 sse */
    private String transport = "sse";

    /** 自定义 HTTP 请求头 */
    private Map<String, String> headers = new HashMap<>();

    /** Provider 级环境变量覆盖 */
    private Map<String, String> env = new HashMap<>();

    /** 请求元数据（如 user_id） */
    private Map<String, Object> metadata = new HashMap<>();

    // ========== getters / setters ==========

    public int getMaxTurns() { return maxTurns; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

    public long getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(long s) { this.connectTimeoutSeconds = s; }

    public long getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(long s) { this.readTimeoutSeconds = s; }

    public long getWriteTimeoutSeconds() { return writeTimeoutSeconds; }
    public void setWriteTimeoutSeconds(long s) { this.writeTimeoutSeconds = s; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long ms) { this.timeoutMs = ms; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int n) { this.maxRetries = n; }

    public long getMaxRetryDelayMs() { return maxRetryDelayMs; }
    public void setMaxRetryDelayMs(long ms) { this.maxRetryDelayMs = ms; }

    public String getTransport() { return transport; }
    public void setTransport(String t) { this.transport = t; }

    public Map<String, String> getHeaders() { return Collections.unmodifiableMap(headers); }
    public void setHeader(String key, String value) { this.headers.put(key, value); }

    public Map<String, String> getEnv() { return Collections.unmodifiableMap(env); }
    public void setEnv(String key, String value) { this.env.put(key, value); }

    public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }
    public void setMetadata(String key, Object value) { this.metadata.put(key, value); }
}
