package com.github.hbq969.ai.pi.core.extension;

import com.github.hbq969.ai.pi.core.api.Context;

import java.util.Map;

/**
 * 上下文构建贡献者契约。
 * 每个实现负责向 {@link Context} 中贡献一类上下文信息。
 */
public interface ContextContributor {

    /** 贡献者唯一标识。 */
    String id();

    /** 执行优先级，数值越小越先执行。默认 0。 */
    default int order() {
        return 0;
    }

    /** 向上下文贡献数据。 */
    void contribute(Context context, ContributionRequest request);

    record ContributionRequest(String sessionId, String workspace, Map<String, Object> attributes) {
        public ContributionRequest {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
