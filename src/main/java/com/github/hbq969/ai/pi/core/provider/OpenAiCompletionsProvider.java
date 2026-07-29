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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Chat Completions API 流式 Provider。
 */
public class OpenAiCompletionsProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson gson = new Gson();

    private final PiCoreConfig config;
    private final OkHttpClient client;

    public OpenAiCompletionsProvider(PiCoreConfig config) {
        this.config = config;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    /**
     * 流式调用 OpenAI Chat Completions API。
     *
     * @param model  模型配置（含 maxTokens）
     * @param ctx    会话上下文
     * @param apiKey API key
     */
    public PiEventStream<StreamEvent, Message.AssistantMessage> stream(
            Model model, Context ctx, String apiKey) {

        var stream = PiEventStream.forStream();

        Thread bg = new Thread(() -> {
            try {
                doStream(model, ctx, apiKey, stream);
            } catch (Exception e) {
                stream.fail(e);
            }
        }, "pi4j-stream");
        bg.setDaemon(true);
        bg.start();

        return stream;
    }

    private void doStream(Model model, Context ctx, String apiKey,
                          PiEventStream<StreamEvent, Message.AssistantMessage> stream) {

        var partial = new Message.AssistantMessage(
                new ArrayList<>(),
                model.api().name(), model.provider(), model.id(),
                new Usage(), StopReason.STOP, null);

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
            partial = new Message.AssistantMessage(
                    partial.content(), partial.api(), partial.provider(), partial.model(),
                    partial.usage(), StopReason.ERROR,
                    StrUtil.sub(e.getMessage(), 0, 500));
            stream.emit(new StreamEvent.Error(partial.error(), partial));
        }
    }

    /**
     * 执行 HTTP 请求。可重试的异常直接向上抛。
     */
    private Response executeHttpRequest(Model model, String apiKey, Context ctx) throws IOException {
        JsonObject body = buildRequestBody(model, ctx);

        Request.Builder reqBuilder = new Request.Builder()
                .url(model.baseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(body), JSON));

        // 自定义 headers
        config.getHeaders().forEach(reqBuilder::header);

        Response response = client.newCall(reqBuilder.build()).execute();
        if (!response.isSuccessful()) {
            String errBody = "";
            try { if (response.body() != null) errBody = response.body().string(); } catch (Exception ignored) {}
            String errMsg = "HTTP " + response.code() + ": " + StrUtil.sub(errBody, 0, 300);
            throw new IOException(errMsg);
        }
        return response;
    }

    private JsonObject buildRequestBody(Model model, Context ctx) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.id());
        body.addProperty("stream", true);
        JsonObject streamOpts = new JsonObject();
        streamOpts.addProperty("include_usage", true);
        body.add("stream_options", streamOpts);
        body.addProperty("store", false);

        if (model.maxTokens() > 0) {
            body.addProperty("max_completion_tokens", model.maxTokens());
        }

        // messages
        JsonArray messages = new JsonArray();
        ctx.systemPrompt().ifPresent(sp -> {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", sp);
            messages.add(sysMsg);
        });

        for (Message msg : ctx.messages()) {
            messages.add(convertMessage(msg));
        }
        body.add("messages", messages);

        // tools
        if (!ctx.tools().isEmpty()) {
            JsonArray toolsArr = new JsonArray();
            for (com.github.hbq969.ai.pi.core.api.Tool tool : ctx.tools()) {
                JsonObject toolObj = new JsonObject();
                toolObj.addProperty("type", "function");
                JsonObject func = new JsonObject();
                func.addProperty("name", tool.name());
                func.addProperty("description", tool.description());
                func.add("parameters", gson.fromJson(tool.jsonSchema(), JsonObject.class));
                toolObj.add("function", func);
                toolsArr.add(toolObj);
            }
            body.add("tools", toolsArr);
        }

        return body;
    }

    private JsonObject convertMessage(Message msg) {
        JsonObject obj = new JsonObject();
        if (msg instanceof Message.UserMessage m) {
            obj.addProperty("role", "user");
            obj.addProperty("content", m.content());
        } else if (msg instanceof Message.AssistantMessage m) {
            obj.addProperty("role", "assistant");
            var text = new StringBuilder();
            JsonArray toolCalls = new JsonArray();
            for (var block : m.content()) {
                if (block instanceof Message.ContentBlock.TextBlock t) {
                    text.append(t.text());
                } else if (block instanceof Message.ContentBlock.ToolCallBlock tc) {
                    JsonObject tcObj = new JsonObject();
                    tcObj.addProperty("id", tc.id());
                    tcObj.addProperty("type", "function");
                    JsonObject func = new JsonObject();
                    func.addProperty("name", tc.name());
                    func.addProperty("arguments", gson.toJson(tc.arguments()));
                    tcObj.add("function", func);
                    toolCalls.add(tcObj);
                }
            }
            if (!text.isEmpty()) {
                obj.addProperty("content", text.toString());
            } else {
                obj.add("content", null);
            }
            if (toolCalls.size() > 0) {
                obj.add("tool_calls", toolCalls);
            }
        } else if (msg instanceof Message.ToolResultMessage m) {
            obj.addProperty("role", "tool");
            obj.addProperty("tool_call_id", m.toolCallId());
            obj.addProperty("content", m.output());
        }
        return obj;
    }

    private void parseStream(okhttp3.ResponseBody bodyStream,
                             Message.AssistantMessage partial,
                             PiEventStream<StreamEvent, Message.AssistantMessage> stream) throws IOException {

        var content = new ArrayList<>(partial.content());
        int textIdx = -1;
        int toolIdx = -1;
        String toolName = "";
        String toolId = "";
        StringBuilder toolArgs = new StringBuilder();
        boolean textStarted = false;
        boolean toolStarted = false;

        try (var reader = new BufferedReader(new InputStreamReader(bodyStream.byteStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6);
                if ("[DONE]".equals(data)) break;

                JsonObject chunk = gson.fromJson(data, JsonObject.class);

                // usage
                if (chunk.has("usage") && !chunk.get("usage").isJsonNull()) {
                    JsonObject usage = chunk.getAsJsonObject("usage");
                    partial.usage().addInput(getLong(usage, "prompt_tokens"));
                    partial.usage().addOutput(getLong(usage, "completion_tokens"));
                    JsonElement details = usage.get("prompt_tokens_details");
                    if (details != null && details.isJsonObject()) {
                        partial.usage().addCacheRead(getLong(details.getAsJsonObject(), "cached_tokens"));
                    }
                }

                JsonElement choicesEl = chunk.get("choices");
                if (choicesEl == null || !choicesEl.isJsonArray()) continue;
                JsonArray choices = choicesEl.getAsJsonArray();
                if (choices.isEmpty()) continue;

                JsonElement choiceEl = choices.get(0);
                if (!choiceEl.isJsonObject()) continue;
                JsonObject choice = choiceEl.getAsJsonObject();

                JsonElement deltaEl = choice.get("delta");
                if (deltaEl == null || !deltaEl.isJsonObject()) continue;
                JsonObject delta = deltaEl.getAsJsonObject();

                // finish_reason
                if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                    partial = new Message.AssistantMessage(
                            content, partial.api(), partial.provider(), partial.model(),
                            partial.usage(), mapStopReason(choice.get("finish_reason").getAsString()), null);
                }

                // text content
                if (delta.has("content") && !delta.get("content").isJsonNull()) {
                    String text = delta.get("content").getAsString();
                    if (!textStarted) {
                        textStarted = true;
                        textIdx = content.size();
                        var block = new Message.ContentBlock.TextBlock(text);
                        content.add(block);
                        partial = new Message.AssistantMessage(
                                content, partial.api(), partial.provider(), partial.model(),
                                partial.usage(), partial.stopReason(), null);
                        stream.emit(new StreamEvent.TextStart(textIdx, partial));
                    } else {
                        var block = (Message.ContentBlock.TextBlock) content.get(textIdx);
                        content.set(textIdx, new Message.ContentBlock.TextBlock(block.text() + text, block.textSignature()));
                        partial = new Message.AssistantMessage(
                                content, partial.api(), partial.provider(), partial.model(),
                                partial.usage(), partial.stopReason(), null);
                        stream.emit(new StreamEvent.TextDelta(textIdx, text, partial));
                    }
                }

                // tool_calls
                JsonArray toolCallsDelta = delta.getAsJsonArray("tool_calls");
                if (toolCallsDelta == null || toolCallsDelta.isEmpty()) continue;

                for (JsonElement tce : toolCallsDelta) {
                    JsonObject tc = tce.getAsJsonObject();
                    // 新 tool call
                    if (tc.has("id") && !tc.get("id").isJsonNull()) {
                        if (toolStarted) {
                            // 结束前一个 tool call
                            finishToolCall(content, toolIdx, toolName, toolId, toolArgs, partial, stream);
                        }
                        toolStarted = true;
                        toolName = "";
                        toolId = tc.get("id").getAsString();
                        toolArgs = new StringBuilder();
                        toolIdx = content.size();
                        content.add(new Message.ContentBlock.ToolCallBlock(toolId, "", new HashMap<>()));
                        partial = new Message.AssistantMessage(
                                content, partial.api(), partial.provider(), partial.model(),
                                partial.usage(), partial.stopReason(), null);
                        stream.emit(new StreamEvent.ToolCallStart(toolIdx, partial));
                    }
                    if (tc.has("function")) {
                        JsonObject func = tc.getAsJsonObject("function");
                        if (func.has("name") && !func.get("name").isJsonNull()) {
                            toolName = func.get("name").getAsString();
                        }
                        if (func.has("arguments") && !func.get("arguments").isJsonNull()) {
                            String args = func.get("arguments").getAsString();
                            toolArgs.append(args);
                            stream.emit(new StreamEvent.ToolCallDelta(toolIdx, args, partial));
                        }
                    }
                }
            }
        }

        // 结束最后一个 tool call
        if (toolStarted) {
            finishToolCall(content, toolIdx, toolName, toolId, toolArgs, partial, stream);
        }

        // 结束文本
        if (textStarted) {
            var block = (Message.ContentBlock.TextBlock) content.get(textIdx);
            stream.emit(new StreamEvent.TextEnd(textIdx, block.text(), partial));
        }

        // done
        partial = new Message.AssistantMessage(
                content, partial.api(), partial.provider(), partial.model(),
                partial.usage(), partial.stopReason(), null);
        stream.emit(new StreamEvent.Done(partial.stopReason(), partial));
    }

    private void finishToolCall(List<Message.ContentBlock> content, int idx,
                                String name, String id, StringBuilder args,
                                Message.AssistantMessage partial,
                                PiEventStream<StreamEvent, Message.AssistantMessage> stream) {
        Map<String, Object> parsedArgs;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = gson.fromJson(args.toString(), Map.class);
            parsedArgs = raw != null ? raw : new HashMap<>();
        } catch (Exception e) {
            parsedArgs = new HashMap<>();
        }
        var tc = new Message.ContentBlock.ToolCallBlock(id, name, parsedArgs);
        content.set(idx, tc);
        partial = new Message.AssistantMessage(
                content, partial.api(), partial.provider(), partial.model(),
                partial.usage(), partial.stopReason(), null);
        stream.emit(new StreamEvent.ToolCallEnd(idx, tc, partial));
    }

    private StopReason mapStopReason(String reason) {
        if (reason == null) return StopReason.STOP;
        return switch (reason) {
            case "stop" -> StopReason.STOP;
            case "length" -> StopReason.LENGTH;
            case "tool_calls", "function_call" -> StopReason.TOOL_USE;
            case "content_filter" -> StopReason.SAFETY;
            default -> StopReason.STOP;
        };
    }

    private long getLong(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && !e.isJsonNull() ? e.getAsLong() : 0;
    }
}
