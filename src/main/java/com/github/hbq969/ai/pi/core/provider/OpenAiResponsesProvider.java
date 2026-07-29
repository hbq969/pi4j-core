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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Responses API 流式 Provider。
 * 端点: {baseUrl}/responses
 * 用于 GPT-OSS 和 OpenAI 新模型。
 */
public class OpenAiResponsesProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson gson = new Gson();

    private final PiCoreConfig config;
    private final OkHttpClient client;

    public OpenAiResponsesProvider(PiCoreConfig config) {
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
        }, "pi4j-responses");
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
                    StrUtil.sub(e.getMessage(), 0, 500));
            stream.emit(new StreamEvent.Error(partial.error(), partial));
        }
    }

    private Response executeHttpRequest(Model model, String apiKey, Context ctx) throws IOException {
        JsonObject body = buildRequestBody(model, ctx);
        Request request = new Request.Builder()
                .url(model.baseUrl() + "/responses")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(body), JSON))
                .build();

        Response response = client.newCall(request).execute();
        if (!response.isSuccessful()) {
            String errBody = "";
            try { if (response.body() != null) errBody = response.body().string(); } catch (Exception ignored) {}
            throw new IOException("HTTP " + response.code() + ": " + StrUtil.sub(errBody, 0, 300));
        }
        return response;
    }

    private JsonObject buildRequestBody(Model model, Context ctx) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.id());
        body.addProperty("stream", true);

        if (model.maxTokens() > 0) {
            body.addProperty("max_output_tokens", model.maxTokens());
        }

        // input[]
        JsonArray input = new JsonArray();
        ctx.systemPrompt().ifPresent(sp -> {
            JsonObject inst = new JsonObject();
            inst.addProperty("role", "system");
            inst.addProperty("content", sp);
            input.add(inst);
        });
        for (Message msg : ctx.messages()) {
            input.add(convertMessage(msg));
        }
        body.add("input", input);

        // tools
        if (!ctx.tools().isEmpty()) {
            JsonArray toolsArr = new JsonArray();
            for (var tool : ctx.tools()) {
                JsonObject toolObj = new JsonObject();
                toolObj.addProperty("type", "function");
                toolObj.addProperty("name", tool.name());
                toolObj.addProperty("description", tool.description());
                toolObj.add("parameters", gson.fromJson(tool.jsonSchema(), JsonObject.class));
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
            for (var block : m.content()) {
                if (block instanceof Message.ContentBlock.TextBlock t) {
                    text.append(t.text());
                }
            }
            obj.addProperty("content", text.toString());

            // tool calls
            JsonArray toolCalls = new JsonArray();
            for (var block : m.content()) {
                if (block instanceof Message.ContentBlock.ToolCallBlock tc) {
                    JsonObject tcObj = new JsonObject();
                    tcObj.addProperty("type", "function_call");
                    tcObj.addProperty("id", tc.id());
                    tcObj.addProperty("name", tc.name());
                    tcObj.addProperty("arguments", gson.toJson(tc.arguments()));
                    toolCalls.add(tcObj);
                }
            }
            if (toolCalls.size() > 0) {
                obj.add("tool_calls", toolCalls);
            }
        } else if (msg instanceof Message.ToolResultMessage trm) {
            obj.addProperty("type", "function_call_output");
            obj.addProperty("call_id", trm.toolCallId());
            obj.addProperty("output", trm.output());
        }
        return obj;
    }

    private void parseStream(okhttp3.ResponseBody bodyStream,
                             Message.AssistantMessage partial,
                             PiEventStream<StreamEvent, Message.AssistantMessage> stream) throws IOException {

        var content = new ArrayList<>(partial.content());
        String line;
        var bis = bodyStream.byteStream();
        var buf = new byte[4096];
        var sb = new StringBuilder();
        boolean streamDone = false;

        while (!streamDone) {
            int n = bis.read(buf);
            if (n == -1) break;

            String chunk = new String(buf, 0, n);
            for (char c : chunk.toCharArray()) {
                if (c == '\n') {
                    line = sb.toString();
                    sb.setLength(0);

                    if (line.isEmpty()) continue;
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6);
                    if ("[DONE]".equals(data)) {
                        streamDone = true;
                        break;
                    }

                    try {
                        JsonObject event = gson.fromJson(data, JsonObject.class);
                        String type = event.get("type").getAsString();

                        switch (type) {
                            case "response.output_text.delta" -> {
                                String text = event.get("delta").getAsString();
                                int idx = findOrCreateTextBlock(content);
                                var old = (Message.ContentBlock.TextBlock) content.get(idx);
                                content.set(idx, new Message.ContentBlock.TextBlock(old.text() + text, old.textSignature()));
                                partial = newMsg(content, partial);
                                stream.emit(new StreamEvent.TextDelta(idx, text, partial));
                            }
                            case "response.output_text.done" -> {
                                String text = event.get("text").getAsString();
                                int idx = findOrCreateTextBlock(content);
                                content.set(idx, new Message.ContentBlock.TextBlock(text, null));
                                partial = newMsg(content, partial);
                                stream.emit(new StreamEvent.TextEnd(idx, text, partial));
                            }
                            case "response.function_call_arguments.delta" -> {
                                String delta = event.get("delta").getAsString();
                                stream.emit(new StreamEvent.ToolCallDelta(0, delta, partial));
                            }
                            case "response.function_call_arguments.done" -> {
                                String args = event.get("arguments").getAsString();
                                String name = event.get("name").getAsString();
                                String callId = event.get("call_id").getAsString();
                                @SuppressWarnings("unchecked")
                                Map<String, Object> argsMap = new HashMap<>();
                                try {
                                    argsMap = gson.fromJson(args, Map.class);
                                } catch (Exception e) {
                                    argsMap = new HashMap<>();
                                }
                                var tc = new Message.ContentBlock.ToolCallBlock(callId, name, argsMap);
                                int idx = content.size();
                                content.add(tc);
                                partial = newMsg(content, partial);
                                stream.emit(new StreamEvent.ToolCallStart(idx, partial));
                                stream.emit(new StreamEvent.ToolCallEnd(idx, tc, partial));
                            }
                            case "response.completed" -> {
                                if (event.has("response")) {
                                    JsonObject resp = event.getAsJsonObject("response");
                                    if (resp.has("usage")) {
                                        JsonObject usageObj = resp.getAsJsonObject("usage");
                                        partial.usage().addInput(getLong(usageObj, "input_tokens"));
                                        partial.usage().addOutput(getLong(usageObj, "output_tokens"));
                                    }
                                }
                                partial = new Message.AssistantMessage(content, partial.api(), partial.provider(),
                                        partial.model(), partial.usage(), StopReason.STOP, null);
                                stream.emit(new StreamEvent.Done(partial.stopReason(), partial));
                            }
                        }
                    } catch (Exception ignored) {
                        // 跳过无法解析的行
                    }
                } else {
                    sb.append(c);
                }
            }
        }
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

    private long getLong(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && !e.isJsonNull() ? e.getAsLong() : 0;
    }
}
