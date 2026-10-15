package com.github.hbq969.ai.pi.core.provider;

import cn.hutool.core.util.StrUtil;
import com.github.hbq969.ai.pi.core.api.Context;
import com.github.hbq969.ai.pi.core.api.Message;
import com.github.hbq969.ai.pi.core.api.Model;
import com.github.hbq969.ai.pi.core.api.StopReason;
import com.github.hbq969.ai.pi.core.api.Usage;
import com.github.hbq969.ai.pi.core.config.PiCoreConfig;
import com.github.hbq969.ai.pi.core.stream.PiEventStream;
import com.github.hbq969.ai.pi.core.stream.StreamEvent;
import com.github.hbq969.ai.pi.core.util.Exceptions;
import com.github.hbq969.ai.pi.core.util.RetryPolicy;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Google Gemini API 流式 Provider。
 * 端点: {baseUrl}/models/{modelId}:streamGenerateContent?alt=sse&key={apiKey}
 */
public class GoogleProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson gson = new Gson();
    private static final String PARAM_REQUEST_TIMEOUT = "request_timeout";
    private static final String PARAM_GENERATION_CONFIG = "generationConfig";
    private static final String PARAM_THINKING = "thinking";
    private static final String PARAM_THINKING_CONFIG = "thinkingConfig";
    private static final String PARAM_INCLUDE_THOUGHTS = "includeThoughts";
    private static final String PARAM_TYPE = "type";
    private static final String THINKING_ENABLED = "enabled";
    private static final Set<String> RESERVED_GENERATION_PARAMS = Set.of(
            PARAM_REQUEST_TIMEOUT, "model", "contents", "systemInstruction", "tools",
            "max_tokens", "maxOutputTokens", PARAM_THINKING);

    private final PiCoreConfig config;
    private final OkHttpClient client;

    public GoogleProvider(PiCoreConfig config) {
        this.config = config;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    public PiEventStream<StreamEvent, Message.AssistantMessage> stream(
            Model model, Context ctx, String apiKey) {

        var stream = PiEventStream.forStream();
        Thread bg = new Thread(() -> {
            try {
                doStream(model, ctx, apiKey, stream);
            } catch (Exception e) {
                stream.fail(e);
            }
        }, "pi4j-google");
        bg.setDaemon(true);
        bg.start();
        return stream;
    }

    private void doStream(Model model, Context ctx, String apiKey,
                          PiEventStream<StreamEvent, Message.AssistantMessage> stream) {

        var partial = new Message.AssistantMessage(new ArrayList<>(), model.api().name(),
                model.provider(), model.id(), new Usage(), StopReason.STOP, null);

        // 带重试的 HTTP 调用（仅 HTTP 层，SSE 解析不重试）
        try {
            Response response = RetryPolicy.withRetry(() -> executeHttpRequest(model, apiKey, ctx),
                    e -> {
                        String msg = e.getMessage();
                        return msg != null && RetryPolicy.isRetryable(msg);
                    },
                    config);

            stream.emit(new StreamEvent.Start(partial));
            var bodyStream = response.body();
            if (bodyStream == null) {
                stream.emit(new StreamEvent.Error("Empty response body", partial));
                return;
            }

            parseStream(bodyStream, partial, stream);
        } catch (Exception e) {
            partial = new Message.AssistantMessage(partial.content(), partial.api(), partial.provider(),
                    partial.model(), partial.usage(), StopReason.ERROR,
                    StrUtil.sub(Exceptions.describe(e), 0, 500));
            stream.emit(new StreamEvent.Error(partial.error(), partial));
        }
    }

    private Response executeHttpRequest(Model model, String apiKey, Context ctx) throws IOException {
        JsonObject body = buildRequestBody(model, ctx);
        String url = model.baseUrl() + "/models/" + model.id() + ":streamGenerateContent?alt=sse&key=" + apiKey;

        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(body), JSON))
                .build();

        Response response = clientFor(model).newCall(request).execute();
        if (!response.isSuccessful()) {
            String errBody = "";
            try { if (response.body() != null) errBody = response.body().string(); } catch (Exception ignored) {}
            throw new IOException("HTTP " + response.code() + ": " + StrUtil.sub(errBody, 0, 300));
        }
        return response;
    }

    private JsonObject buildRequestBody(Model model, Context ctx) {
        JsonObject body = new JsonObject();

        // contents[]
        JsonArray contents = new JsonArray();
        for (Message msg : ctx.messages()) {
            contents.add(convertMessage(msg));
        }
        body.add("contents", contents);

        // systemInstruction
        ctx.systemPrompt().ifPresent(sp -> {
            JsonObject sysInst = new JsonObject();
            JsonArray parts = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("text", sp);
            parts.add(textPart);
            sysInst.add("parts", parts);
            body.add("systemInstruction", sysInst);
        });

        // generationConfig
        JsonObject genConfig = new JsonObject();
        if (model.maxTokens() > 0) {
            genConfig.addProperty("maxOutputTokens", model.maxTokens());
        }
        addConfiguredGenerationParams(genConfig, model.params());
        body.add("generationConfig", genConfig);

        // tools
        if (!ctx.tools().isEmpty()) {
            JsonArray functionDeclarations = new JsonArray();
            for (var tool : ctx.tools()) {
                JsonObject decl = new JsonObject();
                decl.addProperty("name", tool.name());
                decl.addProperty("description", tool.description());
                decl.add("parameters", gson.fromJson(tool.jsonSchema(), JsonObject.class));
                functionDeclarations.add(decl);
            }
            JsonArray toolsArr = new JsonArray();
            JsonObject funcDeclObj = new JsonObject();
            funcDeclObj.add("functionDeclarations", functionDeclarations);
            toolsArr.add(funcDeclObj);
            body.add("tools", toolsArr);
        }

        return body;
    }

    /** 将 Google 的 thinkingConfig 放入 generationConfig，并兼容通用 thinking.enabled 配置。 */
    private void addConfiguredGenerationParams(JsonObject generationConfig, String params) {
        if (params == null || params.isBlank()) return;
        try {
            JsonObject configured = gson.fromJson(params, JsonObject.class);
            if (configured == null) return;

            JsonObject nested = configured.has(PARAM_GENERATION_CONFIG)
                    && configured.get(PARAM_GENERATION_CONFIG).isJsonObject()
                    ? configured.getAsJsonObject(PARAM_GENERATION_CONFIG) : null;
            if (nested != null) {
                nested.entrySet().stream()
                        .filter(e -> !PARAM_REQUEST_TIMEOUT.equals(e.getKey()))
                        .forEach(e -> generationConfig.add(e.getKey(), e.getValue()));
            }
            configured.entrySet().stream()
                    .filter(e -> !RESERVED_GENERATION_PARAMS.contains(e.getKey())
                            && !PARAM_GENERATION_CONFIG.equals(e.getKey()))
                    .forEach(e -> generationConfig.add(e.getKey(), e.getValue()));

            if (configured.has(PARAM_THINKING) && configured.get(PARAM_THINKING).isJsonObject()) {
                JsonObject thinking = configured.getAsJsonObject(PARAM_THINKING);
                if (THINKING_ENABLED.equalsIgnoreCase(thinking.has(PARAM_TYPE)
                        ? thinking.get(PARAM_TYPE).getAsString() : "")) {
                    JsonObject thinkingConfig = generationConfig.has(PARAM_THINKING_CONFIG)
                            && generationConfig.get(PARAM_THINKING_CONFIG).isJsonObject()
                            ? generationConfig.getAsJsonObject(PARAM_THINKING_CONFIG) : new JsonObject();
                    thinkingConfig.addProperty(PARAM_INCLUDE_THOUGHTS, true);
                    generationConfig.add(PARAM_THINKING_CONFIG, thinkingConfig);
                }
            }
        } catch (Exception ignored) {
            // 非法参数不阻断请求，沿用 Provider 默认 generationConfig。
        }
    }

    private OkHttpClient clientFor(Model model) {
        int timeout = requestTimeout(model.params());
        if (timeout <= 0 || timeout == config.getReadTimeoutSeconds()) return client;
        return new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(config.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    private int requestTimeout(String params) {
        if (params == null || params.isBlank()) return 0;
        try {
            JsonObject configured = gson.fromJson(params, JsonObject.class);
            JsonElement timeout = configured != null ? configured.get(PARAM_REQUEST_TIMEOUT) : null;
            return timeout != null && !timeout.isJsonNull() ? timeout.getAsInt() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private JsonObject convertMessage(Message msg) {
        JsonObject obj = new JsonObject();
        if (msg instanceof Message.UserMessage m) {
            obj.addProperty("role", "user");
            JsonArray parts = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("text", m.content());
            parts.add(textPart);
            obj.add("parts", parts);
        } else if (msg instanceof Message.AssistantMessage m) {
            obj.addProperty("role", "model");
            JsonArray parts = new JsonArray();
            for (var block : m.content()) {
                if (block instanceof Message.ContentBlock.TextBlock t) {
                    JsonObject textPart = new JsonObject();
                    textPart.addProperty("text", t.text());
                    parts.add(textPart);
                } else if (block instanceof Message.ContentBlock.ToolCallBlock tc) {
                    JsonObject funcCall = new JsonObject();
                    funcCall.addProperty("name", tc.name());
                    funcCall.add("args", gson.toJsonTree(tc.arguments()));
                    JsonObject funcPart = new JsonObject();
                    funcPart.add("functionCall", funcCall);
                    parts.add(funcPart);
                }
            }
            obj.add("parts", parts);
        } else if (msg instanceof Message.ToolResultMessage trm) {
            obj.addProperty("role", "tool");
            JsonArray parts = new JsonArray();
            JsonObject respPart = new JsonObject();
            JsonObject funcResp = new JsonObject();
            funcResp.addProperty("name", trm.toolName());
            JsonObject resp = new JsonObject();
            resp.addProperty("content", trm.output());
            funcResp.add("response", resp);
            respPart.add("functionResponse", funcResp);
            parts.add(respPart);
            obj.add("parts", parts);
        }
        return obj;
    }

    private void parseStream(okhttp3.ResponseBody bodyStream,
                             Message.AssistantMessage partial,
                             PiEventStream<StreamEvent, Message.AssistantMessage> stream) throws IOException {

        var content = new ArrayList<>(partial.content());
        int thinkingIdx = -1;
        boolean thinkingStarted = false;
        boolean thinkingEnded = false;

        try (var reader = new BufferedReader(new InputStreamReader(bodyStream.byteStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6);

                JsonObject chunk = gson.fromJson(data, JsonObject.class);
                JsonArray candidates = chunk.getAsJsonArray("candidates");
                if (candidates == null || candidates.isEmpty()) continue;

                JsonObject candidate = candidates.get(0).getAsJsonObject();

                // usage
                if (chunk.has("usageMetadata")) {
                    JsonObject um = chunk.getAsJsonObject("usageMetadata");
                    partial.usage().addInput(getLong(um, "promptTokenCount"));
                    partial.usage().addOutput(getLong(um, "candidatesTokenCount"));
                }

                // finishReason
                if (candidate.has("finishReason")) {
                    partial = new Message.AssistantMessage(content, partial.api(), partial.provider(),
                            partial.model(), partial.usage(),
                            mapStopReason(candidate.get("finishReason").getAsString()), null);
                }

                // content
                JsonObject candContent = candidate.getAsJsonObject("content");
                if (candContent == null) continue;

                JsonArray parts = candContent.getAsJsonArray("parts");
                if (parts == null) continue;

                for (JsonElement partEl : parts) {
                    JsonObject part = partEl.getAsJsonObject();

                    if (part.has("text")) {
                        String text = part.get("text").getAsString();
                        boolean thought = part.has("thought") && !part.get("thought").isJsonNull()
                                && part.get("thought").getAsBoolean();
                        if (thought) {
                            if (!thinkingStarted) {
                                thinkingStarted = true;
                                thinkingIdx = content.size();
                                content.add(new Message.ContentBlock.ThinkingBlock(text, null));
                                partial = newMsg(content, partial);
                                stream.emit(new StreamEvent.ThinkingStart(thinkingIdx, partial));
                            } else {
                                var old = (Message.ContentBlock.ThinkingBlock) content.get(thinkingIdx);
                                content.set(thinkingIdx, new Message.ContentBlock.ThinkingBlock(
                                        old.thinking() + text, old.thinkingSignature()));
                                partial = newMsg(content, partial);
                                stream.emit(new StreamEvent.ThinkingDelta(thinkingIdx, text, partial));
                            }
                            continue;
                        }
                        if (thinkingStarted && !thinkingEnded) {
                            var thinking = (Message.ContentBlock.ThinkingBlock) content.get(thinkingIdx);
                            stream.emit(new StreamEvent.ThinkingEnd(thinkingIdx, thinking.thinking(), partial));
                            thinkingEnded = true;
                        }
                        // Gemini 可能在一个 chunk 里返回完整文本，而非增量
                        int textIdx = findOrCreateTextBlock(content);
                        var old = (Message.ContentBlock.TextBlock) content.get(textIdx);
                        content.set(textIdx, new Message.ContentBlock.TextBlock(old.text() + text, old.textSignature()));
                        partial = newMsg(content, partial);
                        stream.emit(new StreamEvent.TextDelta(textIdx, text, partial));

                    } else if (part.has("functionCall")) {
                        if (thinkingStarted && !thinkingEnded) {
                            var thinking = (Message.ContentBlock.ThinkingBlock) content.get(thinkingIdx);
                            stream.emit(new StreamEvent.ThinkingEnd(thinkingIdx, thinking.thinking(), partial));
                            thinkingEnded = true;
                        }
                        JsonObject fc = part.getAsJsonObject("functionCall");
                        String name = fc.get("name").getAsString();
                        JsonObject args = fc.has("args") && fc.get("args").isJsonObject()
                                ? fc.getAsJsonObject("args") : new JsonObject();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> argsMap = gson.fromJson(args, Map.class);

                        var tc = new Message.ContentBlock.ToolCallBlock("gemini-" + content.size(), name, argsMap);
                        int idx = content.size();
                        content.add(tc);
                        partial = newMsg(content, partial);
                        stream.emit(new StreamEvent.ToolCallStart(idx, partial));
                        stream.emit(new StreamEvent.ToolCallEnd(idx, tc, partial));
                    }
                }
            }
        }

        // 结束文本块
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i) instanceof Message.ContentBlock.TextBlock t) {
                stream.emit(new StreamEvent.TextEnd(i, t.text(), partial));
            }
        }

        if (thinkingStarted && !thinkingEnded) {
            var thinking = (Message.ContentBlock.ThinkingBlock) content.get(thinkingIdx);
            stream.emit(new StreamEvent.ThinkingEnd(thinkingIdx, thinking.thinking(), partial));
        }

        partial = new Message.AssistantMessage(content, partial.api(), partial.provider(),
                partial.model(), partial.usage(), partial.stopReason(), null);
        stream.emit(new StreamEvent.Done(partial.stopReason(), partial));
    }

    private int findOrCreateTextBlock(List<Message.ContentBlock> content) {
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i) instanceof Message.ContentBlock.TextBlock) return i;
        }
        content.add(new Message.ContentBlock.TextBlock(""));
        return content.size() - 1;
    }

    private Message.AssistantMessage newMsg(List<Message.ContentBlock> content, Message.AssistantMessage partial) {
        return new Message.AssistantMessage(content, partial.api(), partial.provider(),
                partial.model(), partial.usage(), partial.stopReason(), null);
    }

    private StopReason mapStopReason(String reason) {
        return switch (reason) {
            case "STOP" -> StopReason.STOP;
            case "MAX_TOKENS" -> StopReason.LENGTH;
            case "SAFETY", "RECITATION" -> StopReason.SAFETY;
            default -> StopReason.STOP;
        };
    }

    private long getLong(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && !e.isJsonNull() ? e.getAsLong() : 0;
    }
}
