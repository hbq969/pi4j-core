package com.github.hbq969.ai.pi.core.extension;

import com.github.hbq969.ai.pi.core.agent.AgentEvent;

/** Entry-neutral event sink used by SSE, audit, tests, or future transports. */
@FunctionalInterface
public interface EventSink {

    void accept(AgentEvent event);
}
