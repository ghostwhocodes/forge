package dev.llaith.forge.workflow.state;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

public record LoopState(
        @JsonProperty("loop_id") String loopId,
        @JsonProperty("instance_id") String instanceId,
        @JsonProperty("controller_node") String controllerNode,
        @JsonProperty("entry_node") String entryNode,
        @JsonProperty("controller_visit") long controllerVisit,
        @JsonProperty("iteration_count") long iterationCount,
        @JsonProperty("resolved_budget") @Nullable Long resolvedBudget
) {
    public LoopState withResolvedBudget(long budget) {
        return new LoopState(loopId, instanceId, controllerNode, entryNode, controllerVisit, iterationCount, budget);
    }

    public LoopState withIteration(long iteration) {
        return new LoopState(loopId, instanceId, controllerNode, entryNode, controllerVisit, iteration, resolvedBudget);
    }
}
