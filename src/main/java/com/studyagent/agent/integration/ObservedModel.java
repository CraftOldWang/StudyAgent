package com.studyagent.agent.integration;

import com.studyagent.eval.ModelUsageRecorder;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;

public final class ObservedModel implements Model {
    private final Model delegate;
    private final ModelUsageRecorder recorder;

    public ObservedModel(Model delegate, ModelUsageRecorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        ModelCallScope caller = ModelCallScope.current();
        // Retries must resubscribe outside this boundary so each HTTP attempt consumes the budget.
        GenerateOptions singleAttempt = GenerateOptions.mergeOptions(GenerateOptions.builder()
                .executionConfig(ExecutionConfig.builder().maxAttempts(1).build()).build(), options);
        return Flux.deferContextual(context -> {
            ModelCallScope scope = context.getOrDefault(ModelCallScope.class,
                    caller == null ? ModelCallScope.standalone() : caller);
            String callId = recorder.start(getModelName(), scope, messages.size(), tools, options);
            long started = System.nanoTime();
            AtomicReference<ChatUsage> usage = new AtomicReference<>();
            AtomicBoolean finished = new AtomicBoolean();
            return Flux.defer(() -> delegate.stream(messages, tools, singleAttempt))
                    .doOnNext(response -> {
                        // Stream usage is cumulative per request; summing chunks double-counts tokens.
                        if (response.getUsage() != null) { usage.set(response.getUsage()); }
                    })
                    .doOnComplete(() -> {
                        if (finished.compareAndSet(false, true)) {
                            recorder.finish(callId, "SUCCEEDED", usage.get(), System.nanoTime() - started, null);
                        }
                    })
                    .doOnError(error -> {
                        if (finished.compareAndSet(false, true)) {
                            recorder.finish(callId, "FAILED", usage.get(), System.nanoTime() - started, error);
                        }
                    })
                    .doOnCancel(() -> {
                        if (finished.compareAndSet(false, true)) {
                            recorder.finish(callId, "CANCELLED", usage.get(), System.nanoTime() - started, null);
                        }
                    });
        });
    }

    @Override public String getModelName() { return delegate.getModelName(); }
    @Override public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
    @Override public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
    @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }
}
