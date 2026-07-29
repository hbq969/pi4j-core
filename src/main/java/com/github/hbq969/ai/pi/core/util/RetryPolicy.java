package com.github.hbq969.ai.pi.core.util;

import com.github.hbq969.ai.pi.core.config.PiCoreConfig;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Provider 重试策略。指数退避 + jitter。
 *
 * <p>对照 TS utils/retry.ts 中的 RETRYABLE_PROVIDER_ERROR_PATTERN。
 */
public final class RetryPolicy {

    /** 不可重试错误模式（配额/账单耗尽等） */
    private static final Pattern NON_RETRYABLE = Pattern.compile(
            "insufficient_quota|out of budget|quota exceeded|billing|" +
            "401 Unauthorized|403 Forbidden|invalid.*api.?key|" +
            "model_not_found|does not exist", Pattern.CASE_INSENSITIVE);

    /** 可重试错误模式（临时性服务端/网络故障） */
    private static final Pattern RETRYABLE = Pattern.compile(
            "429|rate.?limit|too many requests|" +
            "500|502|503|504|" +
            "overloaded|service.?unavailable|server.?error|internal.?error|" +
            "timeout|timed.?out|" +
            "connection.?refused|connection.?error|connection.?lost|" +
            "network.?error|fetch failed|getaddrinfo|ENOTFOUND|EAI_AGAIN|" +
            "socket hang up|other side closed|" +
            "ended without|stream ended|" +
            "retry delay",
            Pattern.CASE_INSENSITIVE);

    private RetryPolicy() {}

    /**
     * 判断错误是否可重试。
     * 先检查不可重试模式（优先级更高），再检查可重试模式。
     */
    public static boolean isRetryable(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) return false;
        if (NON_RETRYABLE.matcher(errorMessage).find()) return false;
        return RETRYABLE.matcher(errorMessage).find();
    }

    /**
     * 检查 HTTP 状态码是否可重试。
     */
    public static boolean isRetryableHttpStatus(int statusCode) {
        return statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    /**
     * 计算退避延迟（毫秒），带 jitter。
     *
     * @param attempt    第几次重试（0-based）
     * @param baseDelayMs 基础延迟（毫秒）
     * @return 退避后的延迟
     */
    public static long backoffMs(int attempt, long baseDelayMs) {
        long delay = baseDelayMs * (1L << Math.min(attempt, 10)); // 指数退避，上限 1024 * base
        long jitter = ThreadLocalRandom.current().nextLong(delay / 4); // 25% jitter
        return delay + jitter;
    }

    /**
     * 执行带重试的操作。
     *
     * @param action  要执行的动作
     * @param isRetryable 判断异常是否可重试
     * @param config  配置（maxRetries, maxRetryDelayMs）
     * @param <T>     返回值类型
     * @return 执行结果
     * @throws RetryExhaustedException 重试耗尽
     */
    public static <T> T withRetry(
            RetryableAction<T> action,
            Predicate<Exception> isRetryable,
            PiCoreConfig config) throws Exception {

        Exception lastError = null;
        int maxRetries = config.getMaxRetries();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.execute();
            } catch (Exception e) {
                lastError = e;
                if (attempt >= maxRetries) break;
                if (!isRetryable.test(e)) throw e;

                long delay = backoffMs(attempt, 1000);
                if (delay > config.getMaxRetryDelayMs()) {
                    throw new RetryExhaustedException(
                            "Retry delay " + delay + "ms exceeds max " + config.getMaxRetryDelayMs() + "ms", e);
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw new RetryExhaustedException("Exhausted " + maxRetries + " retries", lastError);
    }

    @FunctionalInterface
    public interface RetryableAction<T> {
        T execute() throws Exception;
    }

    public static class RetryExhaustedException extends Exception {
        public RetryExhaustedException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
