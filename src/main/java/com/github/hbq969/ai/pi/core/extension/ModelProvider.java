package com.github.hbq969.ai.pi.core.extension;

import com.github.hbq969.ai.pi.core.api.Context;
import com.github.hbq969.ai.pi.core.api.Message;
import com.github.hbq969.ai.pi.core.api.Model;
import com.github.hbq969.ai.pi.core.stream.PiEventStream;
import com.github.hbq969.ai.pi.core.stream.StreamEvent;

/** Provider extension contract. Implementations may be supplied by core, ext, or Web modules. */
public interface ModelProvider {

    String id();

    boolean supports(Model model);

    PiEventStream<StreamEvent, Message.AssistantMessage> stream(
            Model model, Context context, String apiKey);
}
