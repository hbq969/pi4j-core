package com.github.hbq969.ai.pi.core;

import com.github.hbq969.ai.pi.core.api.Context;
import com.github.hbq969.ai.pi.core.api.Message;
import com.github.hbq969.ai.pi.core.api.Model;
import com.github.hbq969.ai.pi.core.config.PiCoreConfig;
import com.github.hbq969.ai.pi.core.extension.ModelProvider;
import com.github.hbq969.ai.pi.core.extension.ModelProviderRegistry;
import com.github.hbq969.ai.pi.core.provider.AnthropicProvider;
import com.github.hbq969.ai.pi.core.provider.GoogleProvider;
import com.github.hbq969.ai.pi.core.provider.OpenAiCompletionsProvider;
import com.github.hbq969.ai.pi.core.provider.OpenAiResponsesProvider;
import com.github.hbq969.ai.pi.core.stream.PiEventStream;
import com.github.hbq969.ai.pi.core.stream.StreamEvent;

/**
 * Pi4j 统一入口。按 model.api() 分发到对应 Provider。
 *
 * <p>支持两种模式：
 * <ul>
 *   <li><b>独立模式</b>：使用内置 Provider，通过 {@link #Pi4j(PiCoreConfig)} 或 {@link #stream(Model, Context, String, int)} 静态方法</li>
 *   <li><b>注册表模式</b>：注入 {@link ModelProviderRegistry}，通过 {@link #Pi4j(PiCoreConfig, ModelProviderRegistry)}
 *       构造，dispatch 优先走注册表（支持 AES 解密等扩展），注册表未命中时回退到内置 Provider</li>
 * </ul>
 */
public class Pi4j {

    private static final PiCoreConfig DEFAULT_CONFIG = new PiCoreConfig();
    private static final Pi4j DEFAULT = new Pi4j(DEFAULT_CONFIG);

    final PiCoreConfig config;
    final ModelProviderRegistry registry;
    public final OpenAiCompletionsProvider openAi;
    public final AnthropicProvider anthropic;
    public final GoogleProvider google;
    public final OpenAiResponsesProvider openAiResponses;

    /** 独立模式：仅使用内置 Provider。 */
    public Pi4j(PiCoreConfig config) {
        this(config, null);
    }

    /** 注册表模式：优先通过 registry 分发（支持 AES 解密等扩展），未命中时回退到内置 Provider。 */
    public Pi4j(PiCoreConfig config, ModelProviderRegistry registry) {
        this.config = config;
        this.registry = registry;
        this.openAi = new OpenAiCompletionsProvider(config);
        this.anthropic = new AnthropicProvider(config);
        this.google = new GoogleProvider(config);
        this.openAiResponses = new OpenAiResponsesProvider(config);
    }

    public static Pi4j instance() {
        return DEFAULT;
    }

    // ==================== 静态方法（独立模式） ====================

    public static PiEventStream<StreamEvent, Message.AssistantMessage> stream(
            Model model, Context ctx, String apiKey) {
        return dispatch(DEFAULT, model, ctx, apiKey);
    }

    public static Message.AssistantMessage complete(
            Model model, Context ctx, String apiKey) throws Exception {
        return dispatch(DEFAULT, model, ctx, apiKey).waitForResult();
    }

    // ==================== 实例方法（支持注册表） ====================

    /** 流式调用，优先走注册表。 */
    public PiEventStream<StreamEvent, Message.AssistantMessage> streamInstance(
            Model model, Context ctx, String apiKey) {
        return dispatch(this, model, ctx, apiKey);
    }

    /** 同步调用，优先走注册表。 */
    public Message.AssistantMessage completeInstance(
            Model model, Context ctx, String apiKey) throws Exception {
        return dispatch(this, model, ctx, apiKey).waitForResult();
    }

    // ==================== 内部分发 ====================

    private static PiEventStream<StreamEvent, Message.AssistantMessage> dispatch(
            Pi4j pi, Model model, Context ctx, String apiKey) {
        // 注册表优先
        if (pi.registry != null) {
            ModelProvider provider = pi.registry.findFor(model);
            if (provider != null) {
                return provider.stream(model, ctx, apiKey);
            }
        }
        // 回退到内置 Provider
        return switch (model.api()) {
            case ANTHROPIC_MESSAGES -> pi.anthropic.stream(model, ctx, apiKey);
            case GOOGLE_GENERATIVE_AI -> pi.google.stream(model, ctx, apiKey);
            case OPENAI_RESPONSES -> pi.openAiResponses.stream(model, ctx, apiKey);
            default -> pi.openAi.stream(model, ctx, apiKey);
        };
    }
}
