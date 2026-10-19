package dev.woflo.mycelium.gradle;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.tooling.events.FinishEvent;
import org.gradle.tooling.events.OperationCompletionListener;
import org.gradle.tooling.events.task.TaskFailureResult;
import org.gradle.tooling.events.task.TaskFinishEvent;
import org.gradle.tooling.events.task.TaskSkippedResult;
import org.gradle.tooling.events.task.TaskSuccessResult;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/** Build-scoped completion facts. A success includes Gradle-validated up-to-date and restored outputs. */
public abstract class TaskOutcomes implements BuildService<BuildServiceParameters.None>, OperationCompletionListener {
    public static final String CURRENT = "current";
    private final Map<String, CompletableFuture<String>> completions = new ConcurrentHashMap<>();

    @Override
    public void onFinish(FinishEvent event) {
        if (!(event instanceof TaskFinishEvent task)) return;
        String result = task.getResult() instanceof TaskSuccessResult ? CURRENT
                : task.getResult() instanceof TaskSkippedResult ? "skipped"
                : task.getResult() instanceof TaskFailureResult ? "failed" : "unknown";
        completions.computeIfAbsent(task.getDescriptor().getTaskPath(), ignored -> new CompletableFuture<>()).complete(result);
    }

    Map<String, String> states(Map<String, String> producers, List<String> exclusions) {
        var states = new TreeMap<String, String>();
        for (var producer : producers.entrySet()) {
            CompletableFuture<String> completion = completions.computeIfAbsent(producer.getValue(), ignored -> new CompletableFuture<>());
            // An excluded dependency emits no completion event. With exclusions, only observed facts can be used.
            if (!exclusions.isEmpty()) states.put(producer.getKey(), completion.getNow("not observed with task exclusions"));
            else {
                try {
                    states.put(producer.getKey(), completion.get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException("Evidence collection cancelled");
                } catch (ExecutionException failure) {
                    throw new IllegalStateException("Cannot observe evidence producer " + producer.getValue(), failure.getCause());
                }
            }
        }
        return java.util.Collections.unmodifiableSortedMap(states);
    }
}
