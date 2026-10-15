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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Anthropic Messages API 流式 Provider。
 */
public class AnthropicProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson gson = new Gson();
    private static final String PARAM_REQUEST_TIMEOUT = "request_timeout";
    private static final String PARAM_MODEL = "model";
    private static final String PARAM_MAX_TOKENS = "max_tokens";
    private static final String PARAM_STREAM = "stream";
    private static final String PARAM_SYSTEM = "system";
    private static final String PARAM_MESSAGES = "messages";
    private static final String PARAM_TOOLS = "tools";
    private static final Set<String> RESERVED_REQUEST_PARAMS = Set.of(
            PARAM_REQUEST_TIMEOUT, PARAM_MODEL, PARAM_MAX_TOKENS, PARAM_STREAM,
            PARAM_SYSTEM, PARAM_MESSAGES, PARAM_TOOLS);
    /** SSE 数据行前缀，部分国产网关返回 {@code data:} 时不带空格 */
    private static final String SSE_DATA_PREFIX = "data:";

    /** Anthropic 要求 tool_call_id 只含 [a-zA-Z0-9_-] */
    private static String sanitizeToolCallId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private final PiCoreConfig config;
    private final OkHttpClient client;

    public AnthropicProvider(PiCoreConfig config) {
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
        }, "pi4j-anthropic");
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
        Request request = new Request.Builder()
                .url(model.baseUrl() + "/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
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

    // ==================== 请求构造 ====================

    private JsonObject buildRequestBody(Model model, Context ctx) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.id());
        body.addProperty("max_tokens", model.maxTokens());
        body.addProperty("stream", true);
        addConfiguredParams(body, model.params());

        // system prompt → 顶层字段
        ctx.systemPrompt().ifPresent(sp -> {
            if (sp.startsWith("[") || sp.startsWith("{")) {
                // 已是数组或对象格式，直接解析
                body.add("system", gson.fromJson(sp, JsonElement.class));
            } else {
                body.addProperty("system", sp);
            }
        });

        // messages（处理 Anthropic 特有的 user/assistant 交替、tool_result 包裹规则）
        body.add("messages", convertMessages(ctx.messages(), model));

        // tools
        if (!ctx.tools().isEmpty()) {
            JsonArray toolsArr = new JsonArray();
            for (var tool : ctx.tools()) {
                JsonObject toolObj = new JsonObject();
                toolObj.addProperty("name", tool.name());
                toolObj.addProperty("description", tool.description());
                toolObj.add("input_schema", gson.fromJson(tool.jsonSchema(), JsonObject.class));
                toolsArr.add(toolObj);
            }
            body.add("tools", toolsArr);
        }

        return body;
    }

    /** 将 Anthropic 专属参数（例如 thinking）透传到请求体。 */
    private void addConfiguredParams(JsonObject body, String params) {
        if (params == null || params.isBlank()) return;
        try {
            JsonObject configured = gson.fromJson(params, JsonObject.class);
            if (configured == null) return;
            configured.entrySet().stream()
                    .filter(e -> !RESERVED_REQUEST_PARAMS.contains(e.getKey()))
                    .forEach(e -> body.add(e.getKey(), e.getValue()));
        } catch (Exception ignored) {
            // 非法参数不阻断请求，沿用 Provider 默认请求体。
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

    /**
     * 转换消息为 Anthropic 格式。
     * 规则：
     * - user → {role: "user", content: "..."}
     * - assistant → {role: "assistant", content: [blocks]}
     * - toolResult → {role: "user", content: [{type: "tool_result", ...}]}
     *   连续的 toolResult 合并到一个 user 消息中
     */
    private JsonArray convertMessages(List<Message> messages, Model model) {
        JsonArray result = new JsonArray();

        int i = 0;
        while (i < messages.size()) {
            Message msg = messages.get(i);

            if (msg instanceof Message.UserMessage m) {
                JsonObject obj = new JsonObject();
                obj.addProperty("role", "user");
                obj.addProperty("content", m.content());
                result.add(obj);
                i++;

            } else if (msg instanceof Message.AssistantMessage m) {
                JsonObject obj = new JsonObject();
                obj.addProperty("role", "assistant");

                JsonArray blocks = new JsonArray();
                for (var block : m.content()) {
                    if (block instanceof Message.ContentBlock.TextBlock t) {
                        if (t.text().isBlank()) continue;
                        JsonObject textBlock = new JsonObject();
                        textBlock.addProperty("type", "text");
                        textBlock.addProperty("text", t.text());
                        blocks.add(textBlock);
                    } else if (block instanceof Message.ContentBlock.ThinkingBlock th) {
                        if (th.thinking().isBlank()) continue;
                        JsonObject thinkBlock = new JsonObject();
                        thinkBlock.addProperty("type", "thinking");
                        thinkBlock.addProperty("thinking", th.thinking());
                        thinkBlock.addProperty("signature", th.thinkingSignature() != null ? th.thinkingSignature() : "");
                        blocks.add(thinkBlock);
                    } else if (block instanceof Message.ContentBlock.ToolCallBlock tc) {
                        JsonObject tcBlock = new JsonObject();
                        tcBlock.addProperty("type", "tool_use");
                        tcBlock.addProperty("id", sanitizeToolCallId(tc.id()));
                        tcBlock.addProperty("name", tc.name());
                        tcBlock.add("input", gson.toJsonTree(tc.arguments()));
                        blocks.add(tcBlock);
                    }
                }

                if (blocks.isEmpty()) {
                    // 空的 assistant 消息，占位
                    JsonObject placeholder = new JsonObject();
                    placeholder.addProperty("type", "text");
                    placeholder.addProperty("text", "");
                    blocks.add(placeholder);
                }
                obj.add("content", blocks);
                result.add(obj);
                i++;

            } else if (msg instanceof Message.ToolResultMessage) {
                // 收集连续的 toolResult，合并到一个 user 消息
                JsonArray toolResults = new JsonArray();
                while (i < messages.size() && messages.get(i) instanceof Message.ToolResultMessage tm) {
                    JsonObject tr = new JsonObject();
                    tr.addProperty("type", "tool_result");
                    tr.addProperty("tool_use_id", sanitizeToolCallId(tm.toolCallId()));
                    tr.addProperty("content", tm.output());
                    if (tm.isError()) {
                        tr.addProperty("is_error", true);
                    }
                    toolResults.add(tr);
                    i++;
                }

                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.add("content", toolResults);
                result.add(userMsg);
            }
        }

        return result;
    }

    // ==================== 流式解析 ====================

    private void parseStream(okhttp3.ResponseBody bodyStream,
                             Message.AssistantMessage partial,
                             PiEventStream<StreamEvent, Message.AssistantMessage> stream) throws IOException {

        var content = new ArrayList<>(partial.content());
        // 累积 input_json_delta 的 JSON 片段（key=content 下标），
        // 因为 Anthropic SSE 中 tool_use 的 input 是增量流式传入的，不会自动合并到 ToolCallBlock
        var toolInputAccum = new HashMap<Integer, StringBuilder>();
        // 事件里的 index 是 Anthropic 的块下标；网关漏发 content_block_start（未知块类型）时
        // 直接用 content.size() 定位会整体错位，因此维护 事件 index → content 下标 的映射
        Map<Integer, Integer> contentIndexByEventIndex = new HashMap<>();
        Set<Integer> stoppedBlocks = new HashSet<>();
        int lastContentIndex = -1;
        int dataLineCount = 0;
        String firstNonDataLine = null;
        boolean stopped = false;

        try (var reader = new BufferedReader(new InputStreamReader(bodyStream.byteStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (!line.startsWith(SSE_DATA_PREFIX)) {
                    if (firstNonDataLine == null) firstNonDataLine = line;
                    continue;
                }
                dataLineCount++;
                String data = line.substring(SSE_DATA_PREFIX.length()).trim();
                if (data.isEmpty()) continue;

                JsonObject event;
                try {
                    event = gson.fromJson(data, JsonObject.class);
                } catch (Exception e) {
                    // 心跳、注释等非 JSON 行
                    continue;
                }
                if (event == null || !event.has("type") || event.get("type").isJsonNull()) continue;
                String type = event.get("type").getAsString();

                switch (type) {
                    case "message_start" -> {
                        JsonObject msgObj = event.getAsJsonObject("message");
                        if (msgObj != null && msgObj.has("usage") && msgObj.get("usage").isJsonObject()) {
                            mergeUsage(msgObj.getAsJsonObject("usage"), partial.usage());
                        }
                    }
                    case "content_block_start" -> {
                        JsonObject block = event.getAsJsonObject("content_block");
                        if (block == null || !block.has("type")) continue;
                        String blockType = block.get("type").getAsString();
                        int idx = content.size();
                        contentIndexByEventIndex.put(eventIndex(event, idx), idx);
                        lastContentIndex = idx;

                        switch (blockType) {
                            case "text" -> {
                                content.add(new Message.ContentBlock.TextBlock(""));
                                partial = newMsg(content, partial);
                                stream.emit(new StreamEvent.TextStart(idx, partial));
                            }
                            case "thinking" -> {
                                content.add(new Message.ContentBlock.ThinkingBlock("", ""));
                                partial = newMsg(content, partial);
                                stream.emit(new StreamEvent.ThinkingStart(idx, partial));
                            }
                            case "tool_use" -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> inputMap = block.has("input") && block.get("input").isJsonObject()
                                        ? gson.fromJson(block.get("input"), Map.class) : new HashMap<>();
                                content.add(new Message.ContentBlock.ToolCallBlock(
                                        block.has("id") ? block.get("id").getAsString() : "",
                                        block.has("name") ? block.get("name").getAsString() : "",
                                        inputMap));
                                partial = newMsg(content, partial);
                                stream.emit(new StreamEvent.ToolCallStart(idx, partial));
                            }
                            default -> {
                                // 未知块类型（如 redacted_thinking）：占位保持下标一致
                                content.add(new Message.ContentBlock.TextBlock(""));
                                partial = newMsg(content, partial);
                            }
                        }
                    }
                    case "content_block_delta" -> {
                        JsonObject delta = event.getAsJsonObject("delta");
                        if (delta == null || !delta.has("type")) continue;
                        String deltaType = delta.get("type").getAsString();
                        int idx = resolveContentIndex(event, contentIndexByEventIndex, lastContentIndex);
                        if (idx < 0 || idx >= content.size()) continue;

                        switch (deltaType) {
                            case "text_delta" -> {
                                String text = delta.has("text") ? delta.get("text").getAsString() : "";
                                // 块类型与增量不匹配时（网关下标错乱）退化为新建块，不抛 ClassCastException
                                var old = content.get(idx) instanceof Message.ContentBlock.TextBlock t
                                        ? t : new Message.ContentBlock.TextBlock("");
                                content.set(idx, new Message.ContentBlock.TextBlock(old.text() + text,
                                        old.textSignature()));
                                partial = newMsg(content, partial);
                                stream.emit(new StreamEvent.TextDelta(idx, text, partial));
                            }
                            case "thinking_delta" -> {
                                String thinking = delta.has("thinking") ? delta.get("thinking").getAsString() : "";
                                var old = content.get(idx) instanceof Message.ContentBlock.ThinkingBlock t
                                        ? t : new Message.ContentBlock.ThinkingBlock("", null);
                                content.set(idx, new Message.ContentBlock.ThinkingBlock(
                                        old.thinking() + thinking, old.thinkingSignature()));
                                partial = newMsg(content, partial);
                                stream.emit(new StreamEvent.ThinkingDelta(idx, thinking, partial));
                            }
                            case "signature_delta" -> {
                                String sig = delta.has("signature") ? delta.get("signature").getAsString() : "";
                                if (content.get(idx) instanceof Message.ContentBlock.ThinkingBlock old) {
                                    String oldSig = old.thinkingSignature() != null ? old.thinkingSignature() : "";
                                    content.set(idx, new Message.ContentBlock.ThinkingBlock(
                                            old.thinking(), oldSig + sig));
                                }
                            }
                            case "input_json_delta" -> {
                                String json = delta.has("partial_json") ? delta.get("partial_json").getAsString() : "";
                                // 累积 JSON 片段，content_block_stop 时合并到 ToolCallBlock
                                toolInputAccum.computeIfAbsent(idx, k -> new StringBuilder()).append(json);
                                stream.emit(new StreamEvent.ToolCallDelta(idx, json, partial));
                            }
                            default -> {
                            }
                        }
                    }
                    case "content_block_stop" -> {
                        int idx = resolveContentIndex(event, contentIndexByEventIndex, lastContentIndex);
                        if (idx < 0 || idx >= content.size()) continue;
                        stoppedBlocks.add(idx);
                        partial = finishBlock(idx, content, toolInputAccum, partial, stream);
                    }
                    case "message_delta" -> {
                        JsonObject delta = event.has("delta") ? event.getAsJsonObject("delta") : null;
                        if (delta != null && delta.has("stop_reason") && !delta.get("stop_reason").isJsonNull()) {
                            partial = new Message.AssistantMessage(content, partial.api(), partial.provider(),
                                    partial.model(), partial.usage(),
                                    mapStopReason(delta.get("stop_reason").getAsString()), null);
                        }
                        if (event.has("usage") && event.get("usage").isJsonObject()) {
                            mergeUsage(event.getAsJsonObject("usage"), partial.usage());
                        }
                    }
                    case "message_stop" -> {
                        partial = new Message.AssistantMessage(content, partial.api(), partial.provider(),
                                partial.model(), partial.usage(), partial.stopReason(), null);
                        stopped = true;
                        stream.emit(new StreamEvent.Done(partial.stopReason(), partial));
                    }
                    case "error" -> {
                        // HTTP 200 的 SSE 里携带错误对象（如 overloaded_error），必须显式抛出，
                        // 否则表现为"模型没有回复/没有工具调用"，调用方会一直等结果
                        String message = streamErrorMessage(event);
                        stream.emit(new StreamEvent.Error(message, errorSnapshot(content, partial, message)));
                        return;
                    }
                    default -> {
                        // ping 等事件忽略
                    }
                }
            }
        }

        // 完全没有 SSE 数据行：说明对方返回的不是流式响应（部分网关用 HTTP 200 + JSON 错误体），
        // 不能静默当成空回答，必须把原文抛出来
        if (dataLineCount == 0 && firstNonDataLine != null) {
            String nonSse = StrUtil.sub(firstNonDataLine, 0, 300);
            stream.emit(new StreamEvent.Error(nonSse, errorSnapshot(content, partial, nonSse)));
            return;
        }

        // 流结束但没收到 message_stop（部分网关直接断流）：补齐未闭合的块并发出 Done，
        // 否则调用方会一直阻塞在结果上（表现为页面一直转圈）
        if (!stopped) {
            for (int i = 0; i < content.size(); i++) {
                if (!stoppedBlocks.contains(i)) {
                    partial = finishBlock(i, content, toolInputAccum, partial, stream);
                }
            }
            partial = newMsg(content, partial);
            stream.emit(new StreamEvent.Done(partial.stopReason(), partial));
        }
    }

    /** 事件里的块下标；缺失时用 {@code fallback}。 */
    private int eventIndex(JsonObject event, int fallback) {
        JsonElement index = event.get("index");
        return index != null && !index.isJsonNull() ? index.getAsInt() : fallback;
    }

    /** 事件下标 → content 下标；网关漏发 content_block_start 时退化为最后一个已建立的块。 */
    private int resolveContentIndex(JsonObject event, Map<Integer, Integer> mapping, int fallback) {
        Integer mapped = mapping.get(eventIndex(event, Integer.MIN_VALUE));
        return mapped != null ? mapped : fallback;
    }

    /** 闭合一个内容块：合并工具参数增量并发出对应的 End 事件。 */
    private Message.AssistantMessage finishBlock(int idx, List<Message.ContentBlock> content,
                                                 Map<Integer, StringBuilder> toolInputAccum,
                                                 Message.AssistantMessage partial,
                                                 PiEventStream<StreamEvent, Message.AssistantMessage> stream) {
        var block = content.get(idx);

        if (block instanceof Message.ContentBlock.TextBlock t) {
            stream.emit(new StreamEvent.TextEnd(idx, t.text(), partial));
        } else if (block instanceof Message.ContentBlock.ThinkingBlock th) {
            stream.emit(new StreamEvent.ThinkingEnd(idx, th.thinking(), partial));
        } else if (block instanceof Message.ContentBlock.ToolCallBlock tc) {
            // 合并 input_json_delta 累积的 JSON 到 ToolCallBlock
            Message.ContentBlock.ToolCallBlock finished = tc;
            StringBuilder accum = toolInputAccum.get(idx);
            if (accum != null && accum.length() > 0) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = gson.fromJson(accum.toString(), Map.class);
                    finished = new Message.ContentBlock.ToolCallBlock(tc.id(), tc.name(),
                            args != null ? args : new HashMap<>());
                    content.set(idx, finished);
                    partial = newMsg(content, partial);
                } catch (Exception ignored) {
                    // JSON 解析失败，保留 content_block_start 里的初始参数
                }
            }
            stream.emit(new StreamEvent.ToolCallEnd(idx, finished, partial));
        }
        return partial;
    }

    /** Anthropic 的 error 事件：{@code {"type":"error","error":{"type":"overloaded_error","message":"..."}}}。 */
    private String streamErrorMessage(JsonObject event) {
        JsonElement error = event.get("error");
        if (error != null && !error.isJsonNull()) {
            if (error.isJsonObject()) {
                JsonObject obj = error.getAsJsonObject();
                if (obj.has("message") && !obj.get("message").isJsonNull()) {
                    return obj.get("message").getAsString();
                }
                return obj.toString();
            }
            return error.getAsString();
        }
        return "Unknown stream error: " + event;
    }

    private Message.AssistantMessage errorSnapshot(List<Message.ContentBlock> content,
                                                   Message.AssistantMessage base, String message) {
        return new Message.AssistantMessage(content, base.api(), base.provider(), base.model(),
                base.usage(), StopReason.ERROR, StrUtil.sub(message, 0, 500));
    }

    private Message.AssistantMessage newMsg(List<Message.ContentBlock> content, Message.AssistantMessage partial) {
        return new Message.AssistantMessage(content, partial.api(), partial.provider(),
                partial.model(), partial.usage(), partial.stopReason(), null);
    }

    private void mergeUsage(JsonObject usageObj, Usage usage) {
        usage.addInput(getLong(usageObj, "input_tokens"));
        usage.addOutput(getLong(usageObj, "output_tokens"));
        usage.addCacheRead(getLong(usageObj, "cache_read_input_tokens"));
        usage.addCacheWrite(getLong(usageObj, "cache_creation_input_tokens"));
    }

    private StopReason mapStopReason(String reason) {
        return switch (reason) {
            case "end_turn" -> StopReason.STOP;
            case "max_tokens" -> StopReason.LENGTH;
            case "tool_use" -> StopReason.TOOL_USE;
            case "stop_sequence" -> StopReason.STOP;
            default -> StopReason.STOP;
        };
    }

    private long getLong(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && !e.isJsonNull() ? e.getAsLong() : 0;
    }
}
