package com.github.hbq969.ai.pi.core.util;

/**
 * 异常信息展示工具。
 *
 * <p>HTTP 层失败时 {@link RetryPolicy} 抛出的顶层异常只有「Exhausted 2 retries」这类包装，
 * 真正的原因（HTTP 状态码与响应正文、连接超时）都在 cause 里。Provider 把失败转成
 * {@code StreamEvent.Error} 时只能带一个字符串，若只取 {@code getMessage()}，原因就彻底丢了，
 * 上层（日志、页面）只剩一句无从排查的「重试耗尽」。</p>
 */
public final class Exceptions {

    private Exceptions() {
    }

    /**
     * 把异常连同 cause 链描述成一行：「顶层消息 ← 原因类名: 原因消息 ← ...」。
     *
     * <p>顶层消息保留原样（本身就是人话），cause 补上类名，便于区分是超时还是服务端 5xx；
     * 重复的消息直接跳过，避免出现 {@code timeout ← timeout} 这类噪音。</p>
     *
     * @param e 异常，可为 null
     * @return 一行描述；{@code e} 为 null 时返回空串
     */
    public static String describe(Throwable e) {
        if (e == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Throwable c = e; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            String part = (msg == null || msg.isBlank()) ? c.getClass().getSimpleName()
                    : (c == e ? msg : c.getClass().getSimpleName() + ": " + msg);
            if (sb.indexOf(part) >= 0) continue;
            if (sb.length() > 0) sb.append(" ← ");
            sb.append(part);
        }
        return sb.toString();
    }
}
