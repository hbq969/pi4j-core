package com.github.hbq969.ai.pi.core.extension;

/** Lifecycle contract for MCP connections, vector backends, audit writers, and similar services. */
public interface RuntimeExtension extends AutoCloseable {

    String id();

    default void start() throws Exception {
    }

    default void refresh() throws Exception {
    }

    default Health health() {
        return Health.ok();
    }

    @Override
    default void close() throws Exception {
    }

    record Health(boolean healthy, String message) {
        public static Health ok() { return new Health(true, ""); }
        public static Health unhealthy(String message) { return new Health(false, message); }
    }
}
