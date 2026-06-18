package dev.llaith.forge.storage;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.workflow.state.DerivedRunState;

import java.nio.file.Path;

public record StoredRunContext(
        Path runDir,
        WorkflowSpec spec,
        JsonNode workflowIr,
        JsonNode workflowInterface,
        Path eventsPath,
        DerivedRunState state
) {
}
