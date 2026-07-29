package com.github.hbq969.ai.pi.core.extension;

import com.github.hbq969.ai.pi.core.agent.AgentTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Ext-layer registry that adds source grouping and runtime enable/disable state. */
public final class ToolRegistry {

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public synchronized void register(ToolExtension tool) {
        register(tool, tool.source());
    }

    public synchronized void register(AgentTool tool, String source) {
        Objects.requireNonNull(tool, "tool");
        if (tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        // 不覆盖已有工具（如内置工具被同名 MCP 工具覆盖导致 source 从 "builtin" 变为 "mcp"）
        if (entries.containsKey(tool.name())) {
            return;
        }
        entries.put(tool.name(), new Entry(tool, source == null || source.isBlank() ? "unknown" : source, true));
    }

    public synchronized AgentTool find(String name) {
        Entry entry = entries.get(name);
        return entry == null || !entry.enabled() ? null : entry.tool();
    }

    public synchronized AgentTool require(String name) {
        AgentTool tool = find(name);
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found or disabled: " + name);
        }
        return tool;
    }

    public synchronized void setEnabled(String name, boolean enabled) {
        Entry entry = entries.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }
        entries.put(name, new Entry(entry.tool(), entry.source(), enabled));
    }

    public synchronized List<String> names() {
        return Collections.unmodifiableList(new ArrayList<>(entries.keySet()));
    }

    public synchronized List<String> namesBySource(String source) {
        return entries.values().stream()
                .filter(entry -> entry.source().equals(source))
                .map(entry -> entry.tool().name())
                .toList();
    }

    public synchronized List<AgentTool> enabledTools() {
        return entries.values().stream()
                .filter(Entry::enabled)
                .map(Entry::tool)
                .toList();
    }

    public synchronized Map<String, String> schemas() {
        Map<String, String> schemas = new LinkedHashMap<>();
        entries.values().stream()
                .filter(Entry::enabled)
                .forEach(entry -> schemas.put(entry.tool().name(), entry.tool().jsonSchema()));
        return Collections.unmodifiableMap(schemas);
    }

    private record Entry(AgentTool tool, String source, boolean enabled) {
    }
}
