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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Chat Completions API 流式 Provider。
 */
public class OpenAiCompletionsProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson gson = new Gson();
    private static final String PARAM_REQUEST_TIMEOUT = "request_timeout";
    private static final String PARAM_MODEL = "model";
    private static final String PARAM_STREAM = "stream";
    private static final String PARAM_STREAM_OPTIONS = "stream_options";
    private static final String PARAM_STORE = "store";
    private static final String PARAM_MESSAGES = "messages";
    private static final String PARAM_TOOLS = "tools";
    private static final String PARAM_MAX_TOKENS = "max_tokens";
    private static final String PARAM_MAX_COMPLETION_TOKENS = "max_completion_tokens";
    private static final String PARAM_REASONING_CONTENT = "reasoning_content";
    private static final String PARAM_REASONING = "reasoning";
    private static final String PARAM_REASONING_DETAILS = "reasoning_details";
    private static final String PARAM_THINKING = "thinking";
    private static final String PARAM_CONTENT = "content";
    private static final String PARAM_TEXT = "text";
    private static final String PARAM_CHOICES = "choices";
    private static final String PARAM_FINISH_REASON = "finish_reason";
    private static final String PARAM_DELTA = "delta";
    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_INDEX = "index";
    private static final String PARAM_ID = "id";
    private static final String PARAM_FUNCTION = "function";
    private static final String PARAM_NAME = "name";
    private static final String PARAM_ARGUMENTS = "arguments";
    private static final String PARAM_ERROR = "error";
    private static final String PARAM_CODE = "code";
    private static final String PARAM_BASE_RESP = "base_resp";
    private static final String PARAM_STATUS_CODE = "status_code";
    private static final String PARAM_STATUS_MSG = "status_msg";
    /** SSE 数据行前缀，部分国产网关返回 {@code data:} 时不带空格 */
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE = "[DONE]";
    /** 工具调用标识前缀，服务端未返回 id 时由客户端生成 */
    private static final String TOOL_CALL_ID_PREFIX = "call_";
    /** 工具无输出时回传给模型占位文本，Kimi/MiniMax 等端点要求工具结果不能为空 */
    private static final String EMPTY_TOOL_OUTPUT = "(no output)";
    /** 模型参数：显式指定输出长度上限字段（max_tokens / max_completion_tokens） */
    private static final String PARAM_MAX_TOKENS_FIELD = "max_tokens_field";
    /** 模型参数：是否在后续请求中回传 reasoning_content（默认 Kimi/Moonshot、智谱 GLM 开启） */
    private static final String PARAM_ECHO_REASONING = "echo_reasoning_content";
    /** 模型参数：工具结果消息是否携带 name（默认仅 Kimi/Moonshot 端点开启） */
    private static final String PARAM_TOOL_MESSAGE_NAME = "tool_message_name";
    /** 模型参数：assistant 无文本时 content 是否用空字符串（默认仅 MiniMax 开启，其余用 null） */
    private static final String PARAM_EMPTY_CONTENT_AS_STRING = "empty_content_as_string";
    /** 智谱 GLM 工具参数流式下发开关，不开启时参数在末片一次性返回 */
    private static final String PARAM_TOOL_STREAM = "tool_stream";
    /** 讯飞星火工具调用开关，关闭时 tool_calls 以 JSON 字符串返回而非数组 */
    private static final String PARAM_TOOL_CALLS_SWITCH = "tool_calls_switch";
    /** 并行工具调用开关（部分端点默认只返回一个工具调用） */
    private static final String PARAM_PARALLEL_TOOL_CALLS = "parallel_tool_calls";
    /** 模型参数：显式指定端点家族，用于模型挂在聚合网关后面（按域名识别不出来）时覆盖 */
    private static final String PARAM_ENDPOINT = "endpoint";
    /** 兼容降级最多重试次数（每轮移除一个被服务端拒绝的 OpenAI 专有字段） */
    private static final int MAX_COMPAT_DOWNGRADES = 3;
    /** 可被服务端拒绝后移除的兼容字段（按可疑度排序） */
    private static final List<String> REMOVABLE_REQUEST_FIELDS =
            List.of(PARAM_STREAM_OPTIONS, PARAM_TOOL_STREAM, PARAM_TOOL_CALLS_SWITCH,
                    PARAM_PARALLEL_TOOL_CALLS, PARAM_STORE, PARAM_MAX_COMPLETION_TOKENS);
    /** 错误信息中出现这些词说明服务端在拒绝未知/不支持的请求字段 */
    private static final List<String> UNSUPPORTED_FIELD_HINTS =
            List.of("unsupported", "not support", "does not support", "unknown parameter", "unknown field",
                    "unexpected keyword", "unexpected field", "extra fields", "additional propert",
                    "不支持", "未知参数", "未知字段", "参数不合法", "参数非法", "无法识别", "多余");
    private static final Set<String> RESERVED_REQUEST_PARAMS = Set.of(
            PARAM_REQUEST_TIMEOUT, PARAM_MODEL, PARAM_STREAM, PARAM_STREAM_OPTIONS,
            PARAM_STORE, PARAM_MESSAGES, PARAM_TOOLS, PARAM_MAX_TOKENS,
            PARAM_MAX_COMPLETION_TOKENS, PARAM_MAX_TOKENS_FIELD, PARAM_ECHO_REASONING,
            PARAM_TOOL_MESSAGE_NAME, PARAM_EMPTY_CONTENT_AS_STRING, PARAM_ENDPOINT);

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
                    StrUtil.sub(Exceptions.describe(e), 0, 500));
            stream.emit(new StreamEvent.Error(partial.error(), partial));
        }
    }

    /**
     * 执行 HTTP 请求。可重试的异常直接向上抛。
     *
     * <p>OpenAI 专有字段（store / stream_options / max_completion_tokens）并非所有
     * OpenAI-compatible 端点都接受，部分国产服务端对未知字段直接 400。这里在服务端
     * 明确指名某个字段不可用时，移除该字段重试，避免"换一个模型供应商就完全不可用"。</p>
     */
    private Response executeHttpRequest(Model model, String apiKey, Context ctx) throws IOException {
        JsonObject body = buildRequestBody(model, ctx);
        Response response = sendRequest(model, apiKey, body);
        for (int attempt = 0; attempt < MAX_COMPAT_DOWNGRADES; attempt++) {
            if (response.isSuccessful()) return response;
            String errBody = readErrorBody(response);
            String unsupported = unsupportedInjectedField(errBody);
            if (unsupported == null || !body.has(unsupported)) {
                throw new IOException("HTTP " + response.code() + ": " + StrUtil.sub(errBody, 0, 300));
            }
            // max_completion_tokens 不可用时退回等价的 max_tokens，保留输出上限语义
            JsonElement value = body.remove(unsupported);
            if (PARAM_MAX_COMPLETION_TOKENS.equals(unsupported) && value != null) {
                body.add(PARAM_MAX_TOKENS, value);
            }
            response = sendRequest(model, apiKey, body);
        }
        if (!response.isSuccessful()) {
            String errBody = readErrorBody(response);
            throw new IOException("HTTP " + response.code() + ": " + StrUtil.sub(errBody, 0, 300));
        }
        return response;
    }

    private Response sendRequest(Model model, String apiKey, JsonObject body) throws IOException {
        Request.Builder reqBuilder = new Request.Builder()
                .url(model.baseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(body), JSON));

        // 自定义 headers
        config.getHeaders().forEach(reqBuilder::header);

        return clientFor(model).newCall(reqBuilder.build()).execute();
    }

    private String readErrorBody(Response response) {
        String errBody = "";
        try {
            if (response.body() != null) errBody = response.body().string();
        } catch (Exception ignored) {
        }
        response.close();
        return errBody;
    }

    /** 从错误信息中识别"服务端拒绝的 OpenAI 专有字段"；识别不到返回 null。 */
    private String unsupportedInjectedField(String errBody) {
        if (errBody == null || errBody.isBlank()) return null;
        String text = errBody.toLowerCase();
        boolean rejected = false;
        for (String hint : UNSUPPORTED_FIELD_HINTS) {
            if (text.contains(hint)) {
                rejected = true;
                break;
            }
        }
        if (!rejected) return null;
        for (String field : REMOVABLE_REQUEST_FIELDS) {
            if (text.contains(field)) return field;
        }
        return null;
    }

    private JsonObject buildRequestBody(Model model, Context ctx) {
        JsonObject body = new JsonObject();
        body.addProperty(PARAM_MODEL, model.id());
        body.addProperty(PARAM_STREAM, true);

        JsonObject configured = parseParams(model.params());
        EndpointFamily family = familyOf(model.baseUrl(), configured);

        // stream_options 用于获取 usage；智谱等端点会 400 拒绝该字段（其 usage 本来就在末片返回），
        // 因此默认按端点判断，可用 params.stream_options 覆盖。
        boolean streamOptionsDefault = family != EndpointFamily.ZHIPU && family != EndpointFamily.SPARK;
        if (boolOrDefault(configured, PARAM_STREAM_OPTIONS, streamOptionsDefault)) {
            JsonObject streamOpts = new JsonObject();
            streamOpts.addProperty("include_usage", true);
            body.add(PARAM_STREAM_OPTIONS, streamOpts);
        }
        // store 是 OpenAI 专有字段，非 OpenAI 端点默认不发送
        if (boolOrDefault(configured, PARAM_STORE, family == EndpointFamily.OPENAI)) {
            body.addProperty(PARAM_STORE, false);
        }
        // 智谱 GLM-4.6 及以上：不开 tool_stream 时工具参数在末片一次性下发，
        // 参数生成期间没有任何 SSE 数据，容易被网关按空闲超时断流。
        if (!configured.has(PARAM_TOOL_STREAM) && family == EndpointFamily.ZHIPU
                && !ctx.tools().isEmpty() && supportsToolStream(model.id())) {
            body.addProperty(PARAM_TOOL_STREAM, true);
        }
        // 讯飞星火默认 tool_calls_switch=false，工具调用会以 JSON 字符串返回而不是数组
        if (!configured.has(PARAM_TOOL_CALLS_SWITCH) && family == EndpointFamily.SPARK
                && !ctx.tools().isEmpty()) {
            body.addProperty(PARAM_TOOL_CALLS_SWITCH, true);
        }

        if (model.maxTokens() > 0) {
            body.addProperty(maxTokensField(configured, family), model.maxTokens());
        }

        addConfiguredParams(body, model.params());

        // messages
        JsonArray messages = new JsonArray();
        ctx.systemPrompt().ifPresent(sp -> {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", sp);
            messages.add(sysMsg);
        });

        boolean moonshotFamily = family == EndpointFamily.MOONSHOT;
        // Kimi/Moonshot、智谱 GLM、小米 MiMo 在开启思考并要求工具调用时，必须回传 reasoning_content；
        // DeepSeek 等端点携带该字段会报错，因此默认按端点区分，并允许 params 覆盖。
        MessageCompat compat = new MessageCompat(
                boolOrDefault(configured, PARAM_ECHO_REASONING,
                        moonshotFamily || family == EndpointFamily.ZHIPU || family == EndpointFamily.MIMO),
                boolOrDefault(configured, PARAM_TOOL_MESSAGE_NAME, moonshotFamily),
                boolOrDefault(configured, PARAM_EMPTY_CONTENT_AS_STRING, family == EndpointFamily.MINIMAX));
        for (Message msg : ctx.messages()) {
            messages.add(convertMessage(msg, compat));
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
                func.add("parameters", normalizeToolSchema(tool.jsonSchema(),
                        family == EndpointFamily.ZHIPU));
                toolObj.add("function", func);
                toolsArr.add(toolObj);
            }
            body.add("tools", toolsArr);
        }

        return body;
    }

    /**
     * 将模型配置中的 provider 参数透传给 OpenAI-compatible 接口。
     * 请求结构和传输控制字段由 pi.core 统一维护，避免配置覆盖 messages/tools 等核心字段。
     */
    private void addConfiguredParams(JsonObject body, String params) {
        if (params == null || params.isBlank()) return;
        try {
            JsonObject configured = gson.fromJson(params, JsonObject.class);
            if (configured == null) return;
            configured.entrySet().stream()
                    .filter(e -> !RESERVED_REQUEST_PARAMS.contains(e.getKey()))
                    .forEach(e -> body.add(e.getKey(), e.getValue()));
        } catch (Exception ignored) {
            // 非法参数不应阻断模型请求，沿用 pi.core 默认请求体。
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

    private JsonObject convertMessage(Message msg, MessageCompat compat) {
        JsonObject obj = new JsonObject();
        if (msg instanceof Message.UserMessage m) {
            obj.addProperty("role", "user");
            obj.addProperty(PARAM_CONTENT, m.content());
        } else if (msg instanceof Message.AssistantMessage m) {
            obj.addProperty("role", "assistant");
            var text = new StringBuilder();
            var thinking = new StringBuilder();
            JsonArray toolCalls = new JsonArray();
            for (var block : m.content()) {
                if (block instanceof Message.ContentBlock.TextBlock t) {
                    text.append(t.text());
                } else if (block instanceof Message.ContentBlock.ThinkingBlock th) {
                    if (th.thinking() != null) thinking.append(th.thinking());
                } else if (block instanceof Message.ContentBlock.ToolCallBlock tc) {
                    JsonObject tcObj = new JsonObject();
                    tcObj.addProperty(PARAM_ID, tc.id());
                    tcObj.addProperty("type", "function");
                    JsonObject func = new JsonObject();
                    func.addProperty(PARAM_NAME, tc.name());
                    func.addProperty(PARAM_ARGUMENTS, gson.toJson(tc.arguments()));
                    tcObj.add(PARAM_FUNCTION, func);
                    toolCalls.add(tcObj);
                }
            }
            if (compat.echoReasoning() && !thinking.isEmpty()) {
                obj.addProperty(PARAM_REASONING_CONTENT, thinking.toString());
            }
            // 智谱 GLM 拒绝空字符串 content（要求 null）；MiniMax 则拒绝 null（要求字符串），
            // 因此无文本时按端点决定，默认 null。
            if (text.length() > 0) {
                obj.addProperty(PARAM_CONTENT, text.toString());
            } else if (compat.emptyContentAsString()) {
                obj.addProperty(PARAM_CONTENT, "");
            } else {
                obj.add(PARAM_CONTENT, JsonNull.INSTANCE);
            }
            if (!toolCalls.isEmpty()) {
                obj.add("tool_calls", toolCalls);
            }
        } else if (msg instanceof Message.ToolResultMessage m) {
            obj.addProperty("role", "tool");
            obj.addProperty("tool_call_id", m.toolCallId());
            // Kimi 官方示例要求工具结果消息带 name，用于匹配 tool_call。
            if (compat.toolMessageName() && m.toolName() != null && !m.toolName().isBlank()) {
                obj.addProperty(PARAM_NAME, m.toolName());
            }
            // Kimi/MiniMax 要求工具结果 content 非空。
            obj.addProperty(PARAM_CONTENT,
                    m.output() == null || m.output().isBlank() ? EMPTY_TOOL_OUTPUT : m.output());
        }
        return obj;
    }

    private JsonObject parseParams(String params) {
        if (params == null || params.isBlank()) return new JsonObject();
        try {
            JsonObject configured = gson.fromJson(params, JsonObject.class);
            return configured != null ? configured : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private boolean boolOrDefault(JsonObject configured, String key, boolean defaultValue) {
        if (configured == null || !configured.has(key) || configured.get(key).isJsonNull()) return defaultValue;
        try {
            return configured.get(key).getAsBoolean();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 输出长度上限字段：OpenAI 官方端点使用 max_completion_tokens，
     * 其余 OpenAI-compatible 端点（国产模型）使用 max_tokens；可用
     * params.max_tokens_field 显式覆盖。
     */
    private String maxTokensField(JsonObject configured, EndpointFamily family) {
        String configuredField = configured != null && configured.has(PARAM_MAX_TOKENS_FIELD)
                ? configured.get(PARAM_MAX_TOKENS_FIELD).getAsString() : null;
        if (PARAM_MAX_COMPLETION_TOKENS.equals(configuredField)) return PARAM_MAX_COMPLETION_TOKENS;
        if (PARAM_MAX_TOKENS.equals(configuredField)) return PARAM_MAX_TOKENS;
        return family == EndpointFamily.OPENAI ? PARAM_MAX_COMPLETION_TOKENS : PARAM_MAX_TOKENS;
    }

    /**
     * 识别端点家族：不同厂商对 OpenAI 专有字段、思考回传、空内容的容忍度不同。
     * 识别不出来时按最保守的通用策略处理。
     */
    private EndpointFamily familyOf(String baseUrl, JsonObject configured) {
        if (configured != null && configured.has(PARAM_ENDPOINT)
                && configured.get(PARAM_ENDPOINT).isJsonPrimitive()) {
            String declared = configured.get(PARAM_ENDPOINT).getAsString().trim().toUpperCase();
            for (EndpointFamily candidate : EndpointFamily.values()) {
                if (candidate.name().equals(declared)) return candidate;
            }
        }
        if (baseUrl == null) return EndpointFamily.GENERIC;
        String url = baseUrl.toLowerCase();
        if (url.contains("api.openai.com") || url.contains("openai.azure.com")) {
            return EndpointFamily.OPENAI;
        }
        if (url.contains("moonshot") || url.contains("kimi")) {
            return EndpointFamily.MOONSHOT;
        }
        if (url.contains("bigmodel") || url.contains("zhipu") || url.contains("z.ai/")) {
            return EndpointFamily.ZHIPU;
        }
        if (url.contains("minimax")) {
            return EndpointFamily.MINIMAX;
        }
        if (url.contains("xf-yun") || url.contains("spark-api")) {
            return EndpointFamily.SPARK;
        }
        if (url.contains("xiaomimimo")) {
            return EndpointFamily.MIMO;
        }
        return EndpointFamily.GENERIC;
    }

    /** 智谱 GLM-4.6 及以上支持 tool_stream（更早版本传该参数可能被拒，故不自动开启）。 */
    private boolean supportsToolStream(String modelId) {
        if (modelId == null) return false;
        String id = modelId.toLowerCase();
        return id.contains("glm-4.6") || id.contains("glm-4.7") || id.contains("glm-5");
    }

    /** 端点家族，用于 OpenAI 兼容差异的默认策略。 */
    private enum EndpointFamily {
        OPENAI, MOONSHOT, ZHIPU, MINIMAX, SPARK, MIMO, GENERIC
    }

    /** 消息转换兼容开关。 */
    private record MessageCompat(boolean echoReasoning, boolean toolMessageName,
                                 boolean emptyContentAsString) {
    }

    /**
     * 规整工具参数 schema：
     * <ul>
     *   <li>MiniMax 等端点要求零参数工具必须显式给出 {@code {"type":"object","properties":{}}}，
     *       缺失 parameters 会被 400 拒绝；</li>
     *   <li>{@code $schema} 属于 JSON Schema 方言声明，对工具调用无意义；</li>
     *   <li>智谱 GLM 服务端编译 {@code pattern} 中的 {@code \p{...}} Unicode 属性类会失败并 400，
     *       因此仅对 GLM 剥离该类 pattern，其余端点保持原样。</li>
     * </ul>
     */
    private JsonObject normalizeToolSchema(String jsonSchema, boolean stripUnsupportedPattern) {
        JsonObject schema = new JsonObject();
        if (jsonSchema != null && !jsonSchema.isBlank()) {
            try {
                JsonElement parsed = gson.fromJson(jsonSchema, JsonElement.class);
                if (parsed != null && parsed.isJsonObject()) {
                    schema = parsed.getAsJsonObject();
                }
            } catch (Exception ignored) {
                // schema 非法时退回空对象 schema，避免整个请求失败
            }
        }
        schema.remove("$schema");
        boolean composite = schema.has("anyOf") || schema.has("oneOf") || schema.has("allOf")
                || schema.has("$ref");
        if (!composite) {
            if (!schema.has("type")) {
                schema.addProperty("type", "object");
            }
            JsonElement type = schema.get("type");
            boolean objectSchema = type != null && type.isJsonPrimitive() && "object".equals(type.getAsString());
            if (objectSchema && (!schema.has("properties") || !schema.get("properties").isJsonObject())) {
                schema.add("properties", new JsonObject());
            }
        }
        if (stripUnsupportedPattern) {
            stripUnicodeClassPatterns(schema);
        }
        return schema;
    }

    /** 递归剥离 {@code \p{...}} 形式的 pattern（智谱 GLM 无法编译，会导致整个请求 400）。 */
    private void stripUnicodeClassPatterns(JsonObject schema) {
        for (Map.Entry<String, JsonElement> entry : schema.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) continue;
            if (value.isJsonObject()) {
                stripUnicodeClassPatterns(value.getAsJsonObject());
            } else if (value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) {
                    if (item.isJsonObject()) stripUnicodeClassPatterns(item.getAsJsonObject());
                }
            } else if ("pattern".equals(entry.getKey())
                    && value.isJsonPrimitive() && value.getAsString().contains("\\p{")) {
                entry.setValue(new JsonPrimitive(""));
            }
        }
    }

    private void parseStream(okhttp3.ResponseBody bodyStream,
                             Message.AssistantMessage partial,
                             PiEventStream<StreamEvent, Message.AssistantMessage> stream) throws IOException {

        var content = new ArrayList<>(partial.content());
        int textIdx = -1;
        int thinkingIdx = -1;
        boolean textStarted = false;
        boolean thinkingStarted = false;
        boolean thinkingEnded = false;
        boolean sawDelta = false;

        // 工具调用按 index 归并。国产模型（GLM、Kimi、MiniMax、Qwen 等）在增量切片、
        // id/name 是否重复、并行调用是否交错上并不一致：按"出现新 id"判断新调用会
        // 把一次调用拆成多次，或让并行调用的参数错位、丢失，因此以 index 为主键装配，
        // id 只作为辅助索引，并对缺少 id 的实现生成可回传的调用标识。
        Map<Integer, ToolCallState> toolCallsByIndex = new LinkedHashMap<>();
        Map<String, ToolCallState> toolCallsById = new HashMap<>();
        ToolCallState lastToolCall = null;
        int dataLineCount = 0;
        String firstNonDataLine = null;

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
                if (SSE_DONE.equals(data)) break;

                JsonObject chunk;
                try {
                    chunk = gson.fromJson(data, JsonObject.class);
                } catch (Exception e) {
                    // 心跳、注释等非 JSON 行
                    continue;
                }
                if (chunk == null) continue;

                // HTTP 200 的 SSE 里也可能携带错误对象（部分国产服务端如此），必须显式抛出，
                // 否则表现为"模型没有回复/没有工具调用"，排查成本极高。
                String streamError = extractStreamError(chunk);
                if (streamError != null) {
                    stream.emit(new StreamEvent.Error(streamError,
                            errorSnapshot(content, partial, streamError)));
                    return;
                }

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

                JsonArray choices = asArray(chunk.get(PARAM_CHOICES));
                if (choices == null || choices.isEmpty()) continue;

                JsonElement choiceEl = choices.get(0);
                if (!choiceEl.isJsonObject()) continue;
                JsonObject choice = choiceEl.getAsJsonObject();

                boolean hasDelta = choice.has(PARAM_DELTA) && choice.get(PARAM_DELTA).isJsonObject();
                sawDelta = sawDelta || hasDelta;
                // finish_reason
                if (choice.has(PARAM_FINISH_REASON) && !choice.get(PARAM_FINISH_REASON).isJsonNull()) {
                    partial = new Message.AssistantMessage(
                            content, partial.api(), partial.provider(), partial.model(),
                            partial.usage(), mapStopReason(choice.get(PARAM_FINISH_REASON).getAsString()), null);
                }

                JsonObject delta = hasDelta ? choice.getAsJsonObject(PARAM_DELTA)
                        // 少数兼容网关在 stream=true 下仍以 message 返回完整增量，仅在完全没有 delta 时兼容
                        : (!sawDelta && choice.has(PARAM_MESSAGE) && choice.get(PARAM_MESSAGE).isJsonObject()
                        ? choice.getAsJsonObject(PARAM_MESSAGE) : null);
                if (delta == null) continue;

                // DeepSeek/OpenAI-compatible thinking 增量通常使用 reasoning_content，部分实现使用 thinking。
                String thinking = thinkingDelta(delta);
                if (thinking != null && !thinking.isEmpty()) {
                    if (!thinkingStarted) {
                        thinkingStarted = true;
                        thinkingIdx = content.size();
                        content.add(new Message.ContentBlock.ThinkingBlock(thinking, null));
                        partial = snapshot(content, partial);
                        stream.emit(new StreamEvent.ThinkingStart(thinkingIdx, partial));
                    } else {
                        var block = (Message.ContentBlock.ThinkingBlock) content.get(thinkingIdx);
                        content.set(thinkingIdx,
                                new Message.ContentBlock.ThinkingBlock(block.thinking() + thinking,
                                        block.thinkingSignature()));
                        partial = snapshot(content, partial);
                        stream.emit(new StreamEvent.ThinkingDelta(thinkingIdx, thinking, partial));
                    }
                }

                // text content
                if (delta.has(PARAM_CONTENT) && !delta.get(PARAM_CONTENT).isJsonNull()) {
                    String text = textValue(delta.get(PARAM_CONTENT));
                    if (text != null && !text.isEmpty()) {
                        if (thinkingStarted && !thinkingEnded) {
                            var block = (Message.ContentBlock.ThinkingBlock) content.get(thinkingIdx);
                            stream.emit(new StreamEvent.ThinkingEnd(thinkingIdx, block.thinking(), partial));
                            thinkingEnded = true;
                        }
                        if (!textStarted) {
                            textStarted = true;
                            textIdx = content.size();
                            var block = new Message.ContentBlock.TextBlock(text);
                            content.add(block);
                            partial = snapshot(content, partial);
                            stream.emit(new StreamEvent.TextStart(textIdx, partial));
                        } else {
                            var block = (Message.ContentBlock.TextBlock) content.get(textIdx);
                            content.set(textIdx, new Message.ContentBlock.TextBlock(block.text() + text, block.textSignature()));
                            partial = snapshot(content, partial);
                            stream.emit(new StreamEvent.TextDelta(textIdx, text, partial));
                        }
                    }
                }

                // tool_calls
                JsonArray toolCallsDelta = asArray(delta.get("tool_calls"));
                if (toolCallsDelta == null || toolCallsDelta.isEmpty()) continue;

                for (JsonElement tce : toolCallsDelta) {
                    if (!tce.isJsonObject()) continue;
                    JsonObject tc = tce.getAsJsonObject();
                    String toolCallId = stringValue(tc, PARAM_ID);
                    Integer toolCallIndex = intValue(tc, PARAM_INDEX);

                    ToolCallState state;
                    if (toolCallIndex != null) {
                        state = toolCallsByIndex.get(toolCallIndex);
                    } else if (toolCallId != null && !toolCallId.isEmpty()) {
                        state = toolCallsById.get(toolCallId);
                    } else {
                        state = lastToolCall;
                    }
                    if (state == null) {
                        int key = toolCallIndex != null ? toolCallIndex : (-1 - toolCallsByIndex.size());
                        state = new ToolCallState(content.size());
                        toolCallsByIndex.put(key, state);
                        content.add(new Message.ContentBlock.ToolCallBlock(
                                toolCallId != null ? toolCallId : "", "", new HashMap<>()));
                        partial = snapshot(content, partial);
                        stream.emit(new StreamEvent.ToolCallStart(state.contentIndex, partial));
                    }
                    lastToolCall = state;
                    if (toolCallId != null && !toolCallId.isEmpty()) {
                        state.id = toolCallId;
                        toolCallsById.put(toolCallId, state);
                    }
                    JsonElement funcEl = tc.get(PARAM_FUNCTION);
                    if (funcEl != null && funcEl.isJsonObject()) {
                        JsonObject func = funcEl.getAsJsonObject();
                        String name = stringValue(func, PARAM_NAME);
                        if (name != null && !name.isEmpty() && state.name == null) {
                            state.name = name;
                        }
                        String args = argumentsValue(func.get(PARAM_ARGUMENTS));
                        if (args != null && !args.isEmpty()) {
                            state.args.append(args);
                            stream.emit(new StreamEvent.ToolCallDelta(state.contentIndex, args, partial));
                        }
                    }
                }
            }
        }

        // 完全没有 SSE 数据行：说明对方返回的不是流式响应（部分网关用 HTTP 200 + JSON 错误体，
        // 例如缺少专用会话头）。不能静默当成空回答，必须把原文抛出来。
        if (dataLineCount == 0 && firstNonDataLine != null) {
            String nonSse = StrUtil.sub(firstNonDataLine, 0, 300);
            stream.emit(new StreamEvent.Error(nonSse, errorSnapshot(content, partial, nonSse)));
            return;
        }

        // 结束全部工具调用（按首次出现顺序）
        for (ToolCallState state : toolCallsByIndex.values()) {
            finishToolCall(content, state, partial, stream);
        }

        // 结束文本
        if (textStarted) {
            var block = (Message.ContentBlock.TextBlock) content.get(textIdx);
            stream.emit(new StreamEvent.TextEnd(textIdx, block.text(), partial));
        }

        // 只有思考后没有正文时，也要闭合思考块，保证前端收到完整组件生命周期。
        if (thinkingStarted && !thinkingEnded) {
            var block = (Message.ContentBlock.ThinkingBlock) content.get(thinkingIdx);
            stream.emit(new StreamEvent.ThinkingEnd(thinkingIdx, block.thinking(), partial));
        }

        // done
        partial = new Message.AssistantMessage(
                content, partial.api(), partial.provider(), partial.model(),
                partial.usage(), partial.stopReason(), null);
        stream.emit(new StreamEvent.Done(partial.stopReason(), partial));
    }

    /** 部分服务端在 HTTP 200 的 SSE 中返回错误对象：OpenAI 风格 error，或 MiniMax 风格 base_resp。 */
    private String extractStreamError(JsonObject chunk) {
        JsonElement error = chunk.get(PARAM_ERROR);
        if (error != null && !error.isJsonNull()) {
            if (error.isJsonObject()) {
                JsonObject obj = error.getAsJsonObject();
                String message = stringValue(obj, PARAM_MESSAGE);
                if (message == null || message.isEmpty()) message = stringValue(obj, PARAM_CODE);
                return message != null && !message.isEmpty() ? message : obj.toString();
            }
            return error.getAsString();
        }
        JsonElement baseResp = chunk.get(PARAM_BASE_RESP);
        if (baseResp != null && baseResp.isJsonObject()) {
            JsonObject obj = baseResp.getAsJsonObject();
            Integer statusCode = intValue(obj, PARAM_STATUS_CODE);
            if (statusCode != null && statusCode != 0) {
                String message = stringValue(obj, PARAM_STATUS_MSG);
                return message != null && !message.isEmpty()
                        ? "[" + statusCode + "] " + message : "[" + statusCode + "]";
            }
        }
        return null;
    }

    private String thinkingDelta(JsonObject delta) {
        for (String key : List.of(PARAM_REASONING_CONTENT, PARAM_REASONING_DETAILS, PARAM_REASONING, PARAM_THINKING)) {
            JsonElement element = delta.get(key);
            if (element == null || element.isJsonNull()) continue;
            String value = textValue(element);
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private String textValue(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) return element.getAsString();
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement value = object.get(PARAM_CONTENT);
            if (value == null || value.isJsonNull()) value = object.get(PARAM_TEXT);
            if (value == null || value.isJsonNull()) value = object.get(PARAM_ARGUMENTS);
            return textValue(value);
        }
        if (element.isJsonArray()) {
            StringBuilder result = new StringBuilder();
            for (JsonElement item : element.getAsJsonArray()) result.append(textValue(item));
            return result.toString();
        }
        return "";
    }

    private void finishToolCall(List<Message.ContentBlock> content, ToolCallState state,
                                Message.AssistantMessage partial,
                                PiEventStream<StreamEvent, Message.AssistantMessage> stream) {
        Map<String, Object> parsedArgs;
        String rawArgs = state.args.toString().trim();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = rawArgs.isEmpty() ? new HashMap<>() : gson.fromJson(rawArgs, Map.class);
            parsedArgs = raw != null ? raw : new HashMap<>();
        } catch (Exception e) {
            parsedArgs = new HashMap<>();
        }
        String id = state.id != null && !state.id.isEmpty()
                ? state.id : TOOL_CALL_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String name = state.name != null ? state.name : "";
        var tc = new Message.ContentBlock.ToolCallBlock(id, name, parsedArgs);
        content.set(state.contentIndex, tc);
        stream.emit(new StreamEvent.ToolCallEnd(state.contentIndex, tc, snapshot(content, partial)));
    }

    /** 错误场景的快照：必须带 ERROR 状态，否则上层（AgentLoop）会当成正常空回答。 */
    private Message.AssistantMessage errorSnapshot(List<Message.ContentBlock> content,
                                                   Message.AssistantMessage base, String message) {
        return new Message.AssistantMessage(content, base.api(), base.provider(), base.model(),
                base.usage(), StopReason.ERROR, StrUtil.sub(message, 0, 500));
    }

    private Message.AssistantMessage snapshot(List<Message.ContentBlock> content,
                                              Message.AssistantMessage base) {
        return new Message.AssistantMessage(content, base.api(), base.provider(), base.model(),
                base.usage(), base.stopReason(), null);
    }

    private JsonArray asArray(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonArray()) return element.getAsJsonArray();
        if (element.isJsonObject()) {
            JsonArray array = new JsonArray();
            array.add(element);
            return array;
        }
        // 讯飞星火等端点在开关关闭时把 tool_calls / choices 序列化成 JSON 字符串返回
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            try {
                JsonElement parsed = gson.fromJson(element.getAsString(), JsonElement.class);
                if (parsed != null && (parsed.isJsonArray() || parsed.isJsonObject())) {
                    return asArray(parsed);
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private String stringValue(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        return element.getAsString();
    }

    private Integer intValue(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        try {
            return element.getAsInt();
        } catch (Exception e) {
            return null;
        }
    }

    /** 工具参数增量：标准实现为字符串片段，部分国产实现直接给对象/数组。 */
    private String argumentsValue(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) return element.getAsString();
        if (element.isJsonObject() || element.isJsonArray()) return gson.toJson(element);
        return null;
    }

    /** 流式工具调用装配状态，按 index 归并。 */
    private static final class ToolCallState {
        private final int contentIndex;
        private final StringBuilder args = new StringBuilder();
        private String id;
        private String name;

        private ToolCallState(int contentIndex) {
            this.contentIndex = contentIndex;
        }
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
