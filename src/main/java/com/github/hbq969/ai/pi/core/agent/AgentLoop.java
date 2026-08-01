package com.github.hbq969.ai.pi.core.agent;

import com.github.hbq969.ai.pi.core.Pi4j;
import com.github.hbq969.ai.pi.core.api.Context;
import com.github.hbq969.ai.pi.core.api.Message;
import com.github.hbq969.ai.pi.core.api.Model;
import com.github.hbq969.ai.pi.core.api.StopReason;
import com.github.hbq969.ai.pi.core.config.PiCoreConfig;
import com.github.hbq969.ai.pi.core.execution.CancellationToken;
import com.github.hbq969.ai.pi.core.stream.PiEventStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 工具调用循环（对应 TS agent-loop.ts）。
 * <p>
 * 双层循环结构：
 * <pre>
 * outer: 等待 follow-up 消息，可继续对话
 *   inner: 处理工具调用循环
 *     turn → LLM stream → tool calls → execute → results → repeat
 * </pre>
 */
public class AgentLoop {

    private AgentLoop() {
    }

    // ==================== 公开入口 ====================

    /**
     * 运行 Agent 循环（使用默认 streamFn）。
     */
    public static PiEventStream<AgentEvent, List<Message>> prompt(
            List<Message.UserMessage> userMsgs,
            AgentContext ctx,
            Model model,
            String apiKey,
            PiCoreConfig coreCfg,
            AgentLoopConfig loopCfg) {

        return prompt(userMsgs, ctx, model, apiKey, coreCfg, loopCfg, defaultStreamFn());
    }

    /**
     * 运行 Agent 循环（注入自定义 streamFn）。
     */
    public static PiEventStream<AgentEvent, List<Message>> prompt(
            List<Message.UserMessage> userMsgs,
            AgentContext ctx,
            Model model,
            String apiKey,
            PiCoreConfig coreCfg,
            AgentLoopConfig loopCfg,
            StreamFn streamFn) {

        var stream = new PiEventStream<AgentEvent, List<Message>>(
                e -> e instanceof AgentEvent.AgentEnd,
                e -> ((AgentEvent.AgentEnd) e).messages());

        Thread bg = new Thread(() -> {
            try {
                doPrompt(userMsgs, ctx, model, apiKey, coreCfg, loopCfg, streamFn, stream);
            } catch (Exception e) {
                stream.fail(e);
            }
        }, "pi4j-agent");
        bg.setDaemon(true);
        bg.start();

        return stream;
    }

    private static StreamFn defaultStreamFn() {
        var pi = Pi4j.instance();
        return (m, c, k) -> {
            return switch (m.api()) {
                case ANTHROPIC_MESSAGES -> pi.anthropic.stream(m, c, k);
                case GOOGLE_GENERATIVE_AI -> pi.google.stream(m, c, k);
                case OPENAI_RESPONSES -> pi.openAiResponses.stream(m, c, k);
                default -> pi.openAi.stream(m, c, k);
            };
        };
    }

    // ==================== 核心循环 ====================

    private static void doPrompt(
            List<Message.UserMessage> userMsgs,
            AgentContext ctx,
            Model model,
            String apiKey,
            PiCoreConfig coreCfg,
            AgentLoopConfig loopCfg,
            StreamFn streamFn,
            PiEventStream<AgentEvent, List<Message>> stream) {

        ctx.resetDelta();

        // 初始消息入队
        for (var msg : userMsgs) {
            ctx.addMessage(msg);
            stream.emit(new AgentEvent.MessageStart(msg));
            stream.emit(new AgentEvent.MessageEnd(msg));
        }

        stream.emit(new AgentEvent.AgentStart());

        List<Message> newMessages = new ArrayList<>(userMsgs);
        List<Message> pendingMessages = new ArrayList<>(loopCfg.getSteeringMessages());

        // 当前使用的模型（可能被 prepareNextTurn 切换），用数组引用以在 lambda 中捕获
        Model[] currentModel = { model };

        // ===== outer 循环：follow-up =====
        while (true) {
            stream.cancellationToken().throwIfCancelled();
            boolean hasToolCalls = true;

            // ===== inner 循环：tool calls =====
            int turnCount = 0;
            while (hasToolCalls || !pendingMessages.isEmpty()) {
                stream.cancellationToken().throwIfCancelled();
                turnCount++;
                stream.emit(new AgentEvent.TurnStart());

                // 注入中途消息（steering）
                if (!pendingMessages.isEmpty()) {
                    for (var msg : pendingMessages) {
                        stream.emit(new AgentEvent.MessageStart(msg));
                        stream.emit(new AgentEvent.MessageEnd(msg));
                        ctx.addMessage(msg);
                        newMessages.add(msg);
                    }
                    pendingMessages.clear();
                }

                // 1. LLM 调用
                Context llmCtx = ctx.toContext();
                llmCtx.tools(new ArrayList<>(ctx.tools().values()));

                Message.AssistantMessage assistantMsg;
                try {
                    assistantMsg = streamAndCollect(streamFn, currentModel[0], llmCtx,
                            apiKey, ctx, stream, coreCfg);
                } catch (Exception e) {
                    stream.emit(new AgentEvent.AgentFailure(e));
                    stream.fail(e);
                    return;
                }

                // 最终结果替换
                ctx.replaceLast(assistantMsg);
                newMessages.add(assistantMsg);
                stream.emit(new AgentEvent.MessageEnd(assistantMsg));

                // 2. 检查 stopReason
                if (assistantMsg.stopReason() == StopReason.ERROR) {
                    stream.emit(new AgentEvent.AgentFailure(
                            new IllegalStateException(assistantMsg.error())));
                    stream.emit(new AgentEvent.TurnEnd(assistantMsg, List.of()));
                    stream.emit(new AgentEvent.AgentEnd(newMessages));
                    return;
                }

                // 3. 提取 tool calls
                List<Message.ContentBlock.ToolCallBlock> toolCalls = new ArrayList<>();
                for (var block : assistantMsg.content()) {
                    if (block instanceof Message.ContentBlock.ToolCallBlock tc) {
                        toolCalls.add(tc);
                    }
                }

                // 4. 执行工具（或标记失败的截断调用）
                List<Message.ToolResultMessage> toolResults;
                if (!toolCalls.isEmpty() && assistantMsg.stopReason() == StopReason.LENGTH) {
                    // stopReason=length：所有工具调用参数可能截断，全部标记失败
                    toolResults = failTruncatedToolCalls(toolCalls, stream);
                    hasToolCalls = false;
                } else if (!toolCalls.isEmpty()) {
                    toolResults = executeToolCalls(toolCalls, ctx, stream,
                            stream.cancellationToken(), loopCfg);
                    hasToolCalls = true;
                } else {
                    toolResults = List.of();
                    hasToolCalls = false;
                }

                // 工具结果入上下文
                for (var tr : toolResults) {
                    ctx.addMessage(tr);
                    newMessages.add(tr);
                }

                stream.emit(new AgentEvent.TurnEnd(assistantMsg, toolResults));

                // 5. 回调
                TurnContext tc = new TurnContext(assistantMsg, toolResults, ctx, newMessages);

                // prepareNextTurn（可切换模型）
                PrepareResult pr = Optional.ofNullable(loopCfg.prepareNextTurn(tc))
                        .orElse(PrepareResult.NOOP);
                pr.context().ifPresent(newCtx -> {
                    ctx.addMessages(newCtx.messages());
                });
                pr.model().ifPresent(m -> currentModel[0] = m);

                // shouldStopAfterTurn
                if (loopCfg.shouldStopAfterTurn(tc)) {
                    stream.emit(new AgentEvent.AgentEnd(newMessages));
                    return;
                }

                // 检查新的 steering messages
                pendingMessages = new ArrayList<>(loopCfg.getSteeringMessages());

                // 轮数上限
                if (turnCount >= coreCfg.getMaxTurns()) {
                    hasToolCalls = false;
                }
            }

            // inner 循环结束 → check follow-up
            List<Message> followUp = loopCfg.getFollowUpMessages();
            if (!followUp.isEmpty()) {
                pendingMessages = new ArrayList<>(followUp);
                continue; // 回到 outer 继续 inner
            }

            break; // 彻底结束
        }

        stream.emit(new AgentEvent.AgentEnd(newMessages));
    }

    // ==================== LLM 调用 ====================

    private static Message.AssistantMessage streamAndCollect(
            StreamFn streamFn, Model model, Context llmCtx,
            String apiKey, AgentContext ctx,
            PiEventStream<AgentEvent, List<Message>> agentStream,
            PiCoreConfig coreCfg) throws Exception {

        var holder = new Object() { Message.AssistantMessage msg; };

        var llmStream = streamFn.stream(model, llmCtx, apiKey);
        agentStream.onCancel(() -> llmStream.cancel("agent request cancelled"));
        llmStream.onEvent(e -> {
            if (e instanceof com.github.hbq969.ai.pi.core.stream.StreamEvent.Start s) {
                holder.msg = s.partial();
                ctx.addMessage(s.partial());
                agentStream.emit(new AgentEvent.MessageStart(s.partial()));
            } else if (!(e instanceof com.github.hbq969.ai.pi.core.stream.StreamEvent.Done)
                    && !(e instanceof com.github.hbq969.ai.pi.core.stream.StreamEvent.Error)) {
                if (holder.msg != null) {
                    ctx.replaceLast(e.partial());
                    agentStream.emit(new AgentEvent.MessageUpdate(e, e.partial()));
                }
            }
        });

        return llmStream.waitForResult();
    }

    // ==================== 工具执行 ====================

    private static List<Message.ToolResultMessage> executeToolCalls(
            List<Message.ContentBlock.ToolCallBlock> toolCalls,
            AgentContext ctx,
            PiEventStream<AgentEvent, List<Message>> stream,
            CancellationToken cancellationToken,
            AgentLoopConfig loopCfg) {

        List<Message.ToolResultMessage> results = new ArrayList<>();
        for (var tc : toolCalls) {
            cancellationToken.throwIfCancelled();
            stream.emit(new AgentEvent.ToolExecutionStart(tc.id(), tc.name(), tc.arguments()));
            try {
                AgentTool tool = ctx.tools().get(tc.name());
                if (tool == null) {
                    throw new RuntimeException("Tool not found: " + tc.name());
                }
                ToolResult result = loopCfg.executeTool(tool, tc.id(), tc.arguments());
                stream.emit(new AgentEvent.ToolExecutionEnd(tc.id(), tc.name(), result, false));

                var trm = new Message.ToolResultMessage(tc.id(), tc.name(), result.output(), false);
                results.add(trm);
                stream.emit(new AgentEvent.MessageStart(trm));
                stream.emit(new AgentEvent.MessageEnd(trm));
            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                stream.emit(new AgentEvent.ToolExecutionEnd(tc.id(), tc.name(),
                        new ToolResult(errMsg, Map.of()), true));

                var trm = new Message.ToolResultMessage(tc.id(), tc.name(), errMsg, true);
                results.add(trm);
                stream.emit(new AgentEvent.MessageStart(trm));
                stream.emit(new AgentEvent.MessageEnd(trm));
            }
        }
        return results;
    }

    /**
     * stopReason=length 时，工具调用参数可能被截断。
     * 不执行，全部标记为错误。
     */
    private static List<Message.ToolResultMessage> failTruncatedToolCalls(
            List<Message.ContentBlock.ToolCallBlock> toolCalls,
            PiEventStream<AgentEvent, List<Message>> stream) {

        List<Message.ToolResultMessage> results = new ArrayList<>();
        for (var tc : toolCalls) {
            String errMsg = "Tool call truncated (token limit reached): " + tc.name();
            stream.emit(new AgentEvent.ToolExecutionStart(tc.id(), tc.name(), tc.arguments()));
            stream.emit(new AgentEvent.ToolExecutionEnd(tc.id(), tc.name(),
                    new ToolResult(errMsg, Map.of()), true));

            var trm = new Message.ToolResultMessage(tc.id(), tc.name(), errMsg, true);
            results.add(trm);
            stream.emit(new AgentEvent.MessageStart(trm));
            stream.emit(new AgentEvent.MessageEnd(trm));
        }
        return results;
    }
}
