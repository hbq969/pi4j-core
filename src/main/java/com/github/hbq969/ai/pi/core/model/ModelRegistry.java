package com.github.hbq969.ai.pi.core.model;

import com.github.hbq969.ai.pi.core.api.Api;
import com.github.hbq969.ai.pi.core.api.Model;
import com.github.hbq969.ai.pi.core.api.Usage;

import java.util.*;

/**
 * 模型注册表。管理已知模型配置和费用计算。
 *
 * <p>用法：
 * <pre>{@code
 * Model m = ModelRegistry.get("deepseek", "deepseek-v4-flash");
 * ModelRegistry.calculateCost(m, usage);
 * }</pre>
 */
public final class ModelRegistry {

    private static final Map<String, Map<String, Model>> registry = new LinkedHashMap<>();

    static {
        // ===== DeepSeek =====
        reg("deepseek", new Model("deepseek-chat", "DeepSeek Chat", Api.OPENAI_COMPLETIONS,
                "deepseek", "https://api.deepseek.com", false, 65536, 8192,
                new Model.Cost(0.27, 1.10, 0, 0)));
        reg("deepseek", new Model("deepseek-v4-flash", "DeepSeek V4 Flash", Api.OPENAI_COMPLETIONS,
                "deepseek", "https://api.deepseek.com", false, 128000, 4096,
                new Model.Cost(0.27, 1.10, 0, 0)));
        reg("deepseek", new Model("deepseek-reasoner", "DeepSeek Reasoner", Api.OPENAI_COMPLETIONS,
                "deepseek", "https://api.deepseek.com", true, 65536, 8192,
                new Model.Cost(0.55, 2.19, 0, 0)));

        // ===== OpenAI =====
        reg("openai", new Model("gpt-4o", "GPT-4o", Api.OPENAI_COMPLETIONS,
                "openai", "https://api.openai.com/v1", false, 128000, 16384,
                new Model.Cost(2.50, 10.00, 1.25, 0)));
        reg("openai", new Model("gpt-4o-mini", "GPT-4o Mini", Api.OPENAI_COMPLETIONS,
                "openai", "https://api.openai.com/v1", false, 128000, 16384,
                new Model.Cost(0.15, 0.60, 0.075, 0)));
        reg("openai", new Model("gpt-4.1", "GPT-4.1", Api.OPENAI_COMPLETIONS,
                "openai", "https://api.openai.com/v1", false, 1048576, 32768,
                new Model.Cost(2.00, 8.00, 0.50, 0)));
        reg("openai", new Model("gpt-4.1-mini", "GPT-4.1 Mini", Api.OPENAI_COMPLETIONS,
                "openai", "https://api.openai.com/v1", false, 1048576, 32768,
                new Model.Cost(0.40, 1.60, 0.10, 0)));

        // ===== Anthropic =====
        reg("anthropic", new Model("claude-sonnet-4-20250514", "Claude Sonnet 4", Api.ANTHROPIC_MESSAGES,
                "anthropic", "https://api.anthropic.com/v1", true, 200000, 64000,
                new Model.Cost(3.00, 15.00, 0.30, 3.75)));
        reg("anthropic", new Model("claude-haiku-4-5-20251001", "Claude Haiku 4.5", Api.ANTHROPIC_MESSAGES,
                "anthropic", "https://api.anthropic.com/v1", true, 200000, 32000,
                new Model.Cost(1.00, 5.00, 0.10, 1.25)));
        reg("anthropic", new Model("claude-opus-4-8", "Claude Opus 4.8", Api.ANTHROPIC_MESSAGES,
                "anthropic", "https://api.anthropic.com/v1", true, 200000, 32000,
                new Model.Cost(15.00, 75.00, 1.50, 18.75)));
        reg("anthropic", new Model("claude-fable-5", "Claude Fable 5", Api.ANTHROPIC_MESSAGES,
                "anthropic", "https://api.anthropic.com/v1", true, 200000, 64000,
                new Model.Cost(15.00, 75.00, 1.50, 18.75)));

        // ===== Google =====
        reg("google", new Model("gemini-2.5-pro", "Gemini 2.5 Pro", Api.GOOGLE_GENERATIVE_AI,
                "google", "https://generativelanguage.googleapis.com/v1beta", true,
                1048576, 65536, new Model.Cost(1.25, 10.00, 0, 0)));
        reg("google", new Model("gemini-2.5-flash", "Gemini 2.5 Flash", Api.GOOGLE_GENERATIVE_AI,
                "google", "https://generativelanguage.googleapis.com/v1beta", true,
                1048576, 65536, new Model.Cost(0.15, 0.60, 0, 0)));
    }

    private static void reg(String provider, Model model) {
        registry.computeIfAbsent(provider, k -> new LinkedHashMap<>()).put(model.id(), model);
    }

    private ModelRegistry() {
    }

    // ============ 查询 ============

    /**
     * 按 provider 和 modelId 查找模型，未找到返回 null。
     */
    public static Model get(String provider, String modelId) {
        Map<String, Model> models = registry.get(provider);
        return models != null ? models.get(modelId) : null;
    }

    /**
     * 按 provider 和 modelId 查找模型，未找到抛异常。
     */
    public static Model require(String provider, String modelId) {
        Model m = get(provider, modelId);
        if (m == null) {
            throw new IllegalArgumentException("Model not found: " + provider + "/" + modelId);
        }
        return m;
    }

    /**
     * 返回所有注册的 provider 名称。
     */
    public static Set<String> providers() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    /**
     * 返回指定 provider 下的所有模型。
     */
    public static List<Model> models(String provider) {
        Map<String, Model> models = registry.get(provider);
        return models != null ? List.copyOf(models.values()) : List.of();
    }

    /**
     * 返回所有注册的模型（跨 provider）。
     */
    public static List<Model> allModels() {
        List<Model> all = new ArrayList<>();
        for (var entry : registry.values()) {
            all.addAll(entry.values());
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * 动态注册模型（覆盖同 provider+id 的已有配置）。
     */
    public static void register(Model model) {
        registry.computeIfAbsent(model.provider(), k -> new LinkedHashMap<>()).put(model.id(), model);
    }

    // ============ 费用计算 ============

    /**
     * 根据模型费用和实际用量计算 token 成本（美元）。
     * 公式: cost = (tokens / 1,000,000) × pricePerMillionTokens
     */
    public static double calculateCost(Model model, Usage usage) {
        Model.Cost cost = model.cost();
        return (usage.input() / 1_000_000.0 * cost.input())
                + (usage.output() / 1_000_000.0 * cost.output())
                + (usage.cacheRead() / 1_000_000.0 * cost.cacheRead())
                + (usage.cacheWrite() / 1_000_000.0 * cost.cacheWrite());
    }

    /**
     * 计算费用并格式化为人类可读字符串。
     */
    public static String calculateCostStr(Model model, Usage usage) {
        double cost = calculateCost(model, usage);
        if (cost < 0.01) {
            return String.format("$%.4f", cost);
        }
        return String.format("$%.2f", cost);
    }
}
