package com.github.hbq969.ai.pi.core.extension;

import java.util.Locale;

/** Agent 工具执行模式。 */
public enum OperationMode {
    AUTO("auto"),
    MANUAL("manual"),
    ACCEPT_EDITS("accept-edits"),
    PLAN("plan"),
    BYPASS_PERMISSIONS("bypass-permissions");

    private final String value;

    OperationMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static OperationMode parse(String value) {
        if (value == null) throw new IllegalArgumentException("mode is required");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (OperationMode mode : values()) {
            if (mode.value.equals(normalized)) return mode;
        }
        throw new IllegalArgumentException("Unknown mode: " + value
                + ". Use auto, manual, accept-edits, plan or bypass-permissions.");
    }
}
