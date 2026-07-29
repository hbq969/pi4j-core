package com.github.hbq969.ai.pi.core.extension;

import com.github.hbq969.ai.pi.core.api.Model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Ordered, replaceable registry for model provider extensions. */
public final class ModelProviderRegistry {

    private final Map<String, ModelProvider> providers = new LinkedHashMap<>();

    public synchronized ModelProvider register(ModelProvider provider) {
        Objects.requireNonNull(provider, "provider");
        if (provider.id() == null || provider.id().isBlank()) {
            throw new IllegalArgumentException("Provider id must not be blank");
        }
        return providers.put(provider.id(), provider);
    }

    public synchronized ModelProvider find(String id) {
        return providers.get(id);
    }

    public synchronized ModelProvider findFor(Model model) {
        return providers.values().stream()
                .filter(provider -> provider.supports(model))
                .findFirst()
                .orElse(null);
    }

    public synchronized ModelProvider require(String id) {
        ModelProvider provider = find(id);
        if (provider == null) {
            throw new IllegalArgumentException("Model provider not found: " + id);
        }
        return provider;
    }

    public synchronized List<String> ids() {
        return Collections.unmodifiableList(new ArrayList<>(providers.keySet()));
    }

    public synchronized List<ModelProvider> all() {
        return List.copyOf(providers.values());
    }
}
