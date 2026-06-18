package dev.llaith.forge.workflow.runner;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record PendingDispatch(
        String dispatchId,
        String nodeId,
        String runner,
        List<String> command,
        @Nullable String cwd,
        Map<String, String> env,
        List<String> inputPaths,
        List<String> outputPaths,
        @Nullable String stdinPath,
        @Nullable String message,
        @Nullable Long timeoutMs
) {
    public PendingDispatch {
        command = List.copyOf(command);
        env = Map.copyOf(new TreeMap<>(env));
        inputPaths = List.copyOf(inputPaths);
        outputPaths = List.copyOf(outputPaths);
    }
}
