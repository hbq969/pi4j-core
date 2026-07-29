package com.github.hbq969.ai.pi.core.session;

import com.github.hbq969.ai.pi.core.extension.RuntimeExtension;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Agent 会话管理器——管理 SSE 连接、进程追踪与取消信号。
 * 每个 Agent 对话周期绑定一个会话。
 */
public interface SessionManager extends RuntimeExtension {

    /**
     * 注册新会话（或替换已有会话）。
     *
     * @param sessionId     会话 ID
     * @param userName      用户名
     * @param workspacePath 工作空间绝对路径
     * @return 会话句柄
     */
    SessionHandle register(String sessionId, String userName, String workspacePath);

    /** 获取会话句柄，不存在返回 null。 */
    SessionHandle get(String sessionId);

    /** 移除并关闭会话。 */
    void remove(String sessionId);

    /** 获取某用户的所有活跃会话。 */
    List<SessionHandle> getByUser(String userName);

    /** 取消指定会话。 */
    void cancel(String sessionId);

    /** Agent 线程池，用于异步执行对话流程。 */
    ExecutorService getExecutor();

    /** 会话句柄——控制单个 SSE 连接的生命周期。 */
    interface SessionHandle {
        String sessionId();
        String userName();
        String workspacePath();

        /** 刷新最后活动时间。 */
        void touch();

        /** 标记取消并清理关联进程。 */
        void cancel();

        /** 如果已取消则抛出异常。 */
        void checkCancel();

        boolean isCancelled();
    }

    /** 会话已被取消异常。 */
    final class SessionCancelledException extends RuntimeException {
        public SessionCancelledException(String sessionId) {
            super("会话已取消: " + sessionId);
        }
    }
}
