package com.github.hbq969.ai.pi.core.extension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Ordered, replaceable registry for executable tool extensions. */
public final class ToolExtensionRegistry {

    private final Map<String, ToolExtension> tools = new LinkedHashMap<>();

    public synchronized ToolExtension register(ToolExtension tool) {
        Objects.requireNonNull(tool, "tool");
        if (tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        return tools.put(tool.name(), tool);
    }

    public synchronized ToolExtension find(String name) {
        return tools.get(name);
    }

    public synchronized ToolExtension require(String name) {
        ToolExtension tool = find(name);
        if (tool == null) {
            throw new IllegalArgumentException("Tool extension not found: " + name);
        }
        return tool;
    }

    public synchronized List<String> names() {
        return Collections.unmodifiableList(new ArrayList<>(tools.keySet()));
    }

    public synchronized List<ToolExtension> all() {
        return List.copyOf(tools.values());
    }
}
