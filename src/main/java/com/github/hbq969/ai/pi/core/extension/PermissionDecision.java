package com.github.hbq969.ai.pi.core.extension;

/** 权限策略给出的执行决定。 */
public record PermissionDecision(boolean allowed, boolean requiresApproval, String reason) {

    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(true, false, reason);
    }

    public static PermissionDecision approve(String reason) {
        return new PermissionDecision(false, true, reason);
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(false, false, reason);
    }
}
