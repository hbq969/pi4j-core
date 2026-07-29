package com.github.hbq969.ai.pi.core.workspace;

import com.github.hbq969.ai.pi.core.extension.RuntimeExtension;

import java.nio.file.Path;

/**
 * 工作空间路径管理器——定义文件系统边界与路径解析契约。
 * 每个 Agent 会话绑定一个工作空间，所有文件操作须经此管理器校验。
 */
public interface WorkspaceManager extends RuntimeExtension {

    /**
     * 将相对路径解析为绝对路径，同时校验不超出工作空间边界。
     *
     * @param workspaceRoot 工作空间根目录（绝对路径）
     * @param relativePath  用户/LLM 提供的相对路径
     * @return 解析后的绝对路径
     * @throws WorkspaceAccessException 路径超出边界或访问配置文件时
     */
    Path resolvePath(Path workspaceRoot, String relativePath);

    /**
     * 检查目标路径是否在工作空间边界内。
     *
     * @param workspaceRoot 工作空间根目录
     * @param target        待检查的路径
     * @return true 表示在边界内
     */
    boolean isWithinBoundary(Path workspaceRoot, Path target);

    /** 路径越界或访问受限异常。 */
    final class WorkspaceAccessException extends RuntimeException {
        public WorkspaceAccessException(String message) {
            super(message);
        }
    }
}
