package com.github.hbq969.ai.pi.core.extension;

import com.github.hbq969.ai.pi.core.api.Context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stable-order context contributor registry. */
public final class ContextRegistry {

    private final List<ContextContributor> contributors = new ArrayList<>();

    public synchronized void register(ContextContributor contributor) {
        contributors.removeIf(existing -> existing.id().equals(contributor.id()));
        contributors.add(contributor);
        contributors.sort(Comparator.comparingInt(ContextContributor::order).thenComparing(ContextContributor::id));
    }

    public synchronized void contribute(Context context,
                                        com.github.hbq969.ai.pi.core.extension.ContextContributor.ContributionRequest request) {
        for (ContextContributor contributor : contributors) {
            contributor.contribute(context, request);
        }
    }

    public synchronized List<String> ids() {
        return contributors.stream().map(ContextContributor::id).toList();
    }
}
