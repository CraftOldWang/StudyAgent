package com.studyagent.agent.integration;

import java.util.UUID;
import java.util.function.Function;
import reactor.util.context.Context;

public record ModelCallScope(String traceId, String operation) {
    private static final ThreadLocal<ModelCallScope> CURRENT = new ThreadLocal<>();

    public static ModelCallScope current() {
        return CURRENT.get();
    }

    public static void bind(ModelCallScope scope) {
        CURRENT.set(scope);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static ModelCallScope standalone() {
        return new ModelCallScope(UUID.randomUUID().toString(), "UNSCOPED");
    }

    public static Function<Context, Context> capture() {
        ModelCallScope scope = current() == null ? standalone() : current();
        return context -> context.put(ModelCallScope.class, scope);
    }
}
