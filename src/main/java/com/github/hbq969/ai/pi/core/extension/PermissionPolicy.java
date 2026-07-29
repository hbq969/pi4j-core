package com.github.hbq969.ai.pi.core.extension;

import java.util.Map;

/** 与终端交互无关的工具权限策略。 */
public final class PermissionPolicy {

    private PermissionPolicy() {
    }

    public static PermissionDecision evaluate(OperationMode mode, String toolName,
                                              Map<String, Object> arguments) {
        ToolRisk risk = classify(toolName, arguments);
        return switch (mode) {
            case BYPASS_PERMISSIONS -> PermissionDecision.allow("bypass-permissions");
            case PLAN -> risk == ToolRisk.READ_ONLY
                    ? PermissionDecision.allow("read-only analysis")
                    : PermissionDecision.deny("plan mode only permits read-only tools");
            case ACCEPT_EDITS -> risk == ToolRisk.READ_ONLY || risk == ToolRisk.WRITE
                    ? PermissionDecision.allow("accepted workspace edit")
                    : PermissionDecision.approve(approvalReason(risk));
            case MANUAL -> PermissionDecision.approve("manual mode requires approval");
            case AUTO -> risk == ToolRisk.READ_ONLY
                    ? PermissionDecision.allow("read-only tool")
                    : PermissionDecision.approve(approvalReason(risk));
        };
    }

    public static ToolRisk classify(String toolName, Map<String, Object> arguments) {
        if (toolName == null) return ToolRisk.DANGEROUS;
        return switch (toolName) {
            case "read", "list", "glob", "grep" -> ToolRisk.READ_ONLY;
            case "write", "edit" -> ToolRisk.WRITE;
            case "bash" -> isDangerousCommand(arguments) ? ToolRisk.DANGEROUS : ToolRisk.COMMAND;
            case "verify" -> ToolRisk.COMMAND;
            default -> ToolRisk.DANGEROUS;
        };
    }

    private static boolean isDangerousCommand(Map<String, Object> arguments) {
        Object command = arguments == null ? null : arguments.get("command");
        return CommandRiskClassifier.classify(command == null ? null : command.toString())
                == CommandRiskClassifier.Risk.DANGEROUS;
    }

    private static String approvalReason(ToolRisk risk) {
        return risk == ToolRisk.DANGEROUS
                ? "dangerous command requires approval"
                : "command requires approval";
    }
}
