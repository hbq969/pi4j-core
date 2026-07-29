package com.github.hbq969.ai.pi.core.extension;

import java.util.Locale;
import java.util.regex.Pattern;

/** 识别 shell 命令风险，不依赖终端或具体入口。 */
public final class CommandRiskClassifier {

    private static final Pattern DANGEROUS = Pattern.compile(
            "(^|[;&|]\\s*|\\s)(rm|sudo|mkfs|shutdown|reboot)(\\s|$)"
                    + "|(^|[;&|]\\s*|\\s)git\\s+(reset|clean)(\\s|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WORKSPACE_MODIFY = Pattern.compile(
            "(>|>>|\\btee\\b|\\btouch\\b|\\bmkdir\\b|\\bcp\\b|\\bmv\\b|\\bchmod\\b"
                    + "|\\bgit\\s+(add|commit|checkout|switch|restore)\\b)",
            Pattern.CASE_INSENSITIVE);

    private CommandRiskClassifier() {
    }

    public static Risk classify(String command) {
        if (command == null || command.isBlank()) return Risk.DANGEROUS;
        String normalized = command.toLowerCase(Locale.ROOT);
        if (DANGEROUS.matcher(normalized).find()) return Risk.DANGEROUS;
        if (WORKSPACE_MODIFY.matcher(normalized).find()) return Risk.WORKSPACE_MODIFY;
        return Risk.NORMAL;
    }

    public enum Risk {
        NORMAL,
        WORKSPACE_MODIFY,
        DANGEROUS
    }
}
