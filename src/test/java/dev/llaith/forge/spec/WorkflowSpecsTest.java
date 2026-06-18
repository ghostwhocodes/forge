package dev.llaith.forge.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.llaith.forge.ForgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class WorkflowSpecsTest {
    @TempDir
    private Path tempDir;

    @Test
    void sourceWorkflowLoadingMaterializesPromptAndCommandFilesBeforeStrictValidation() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("source-workflow"));
        Files.createDirectories(root.resolve("prompts"));
        Files.createDirectories(root.resolve("scripts"));
        Files.writeString(root.resolve("prompts/plan.txt"), "Write the plan.\n");
        Files.writeString(root.resolve("scripts/publish.sh"), "#!/usr/bin/env bash\n");
        Path specPath = root.resolve("workflow.json");
        Files.writeString(specPath, structuredWorkflowWithExternalFiles(""));

        WorkflowSpec spec = WorkflowSpecs.load(specPath);

        JsonNode plan = spec.nodes().getFirst();
        JsonNode publish = spec.nodes().get(1);
        assertThat(plan.has("prompt_file")).isFalse();
        assertThat(plan.get("prompt_template").asText()).isEqualTo("Write the plan.\n");
        assertThat(publish.has("command_file")).isFalse();
        assertThat(publish.get("command").get(0).asText()).isEqualTo(root.resolve("scripts/publish.sh").toString());

        Path invalid = root.resolve("invalid.json");
        Files.writeString(invalid, structuredWorkflowWithExternalFiles("\"unexpected\" : true,"));
        assertThatThrownBy(() -> WorkflowSpecs.load(invalid))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("unknown field 'unexpected'");
    }

    @Test
    void structuredLoweringDerivesRustShapedIrWithStableSpecHashAndLoopRoutes() throws Exception {
        Path specPath = tempDir.resolve("structured.json");
        Files.writeString(specPath, """
                {
                  "version": 2,
                  "workflow_id": "structured_test",
                  "entry_node": "plan",
                  "artifacts": {
                    "request": { "path": "artifacts/request.json", "media_type": "application/json" },
                    "policy": { "path": "artifacts/policy.json", "media_type": "application/json" }
                  },
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "plan",
                      "inputs": ["request"],
                      "outputs": ["policy"],
                      "command": ["sh", "-c", "true"],
                      "route": { "mode": "always", "to": "review" }
                    },
                    {
                      "kind": "human",
                      "id": "review",
                      "inputs": ["policy"],
                      "outputs": [],
                      "fields": [
                        {
                          "name": "approval",
                          "required": true,
                          "kind": "enum",
                          "allowed_values": ["accept", "retry"]
                        }
                      ],
                      "route": {
                        "mode": "by_field",
                        "field": "approval",
                        "cases": [
                          { "equals": "accept", "to": "__complete__" },
                          { "equals": "retry", "continue_loop": "review_loop" }
                        ]
                      }
                    }
                  ],
                  "loops": [
                    {
                      "id": "review_loop",
                      "controller_node": "review",
                      "entry_node": "plan",
                      "budget": { "kind": "artifact_field", "artifact": "policy", "field": "max_iterations" },
                      "on_exhaust": { "to": "__escalate__", "reason": "review loop exhausted" }
                    }
                  ]
                }
                """);
        WorkflowSpec spec = WorkflowSpecs.load(specPath);

        JsonNode ir = WorkflowSpecCompiler.compile(spec).workflowIr();
        JsonNode secondIr = WorkflowSpecCompiler.compile(spec).workflowIr();

        assertThat(spec.effectiveRoutingMode()).isEqualTo("structured");
        assertThat(spec.routingModeSource()).isEqualTo("version_default");
        assertThat(spec.routingModeWarnings()).isEmpty();
        assertThat(ir.get("spec_version").asInt()).isEqualTo(2);
        assertThat(ir.get("routing_mode").asText()).isEqualTo("structured");
        assertThat(ir.get("source_spec_sha256").asText()).hasSize(64);
        assertThat(secondIr.get("source_spec_sha256").asText()).isEqualTo(ir.get("source_spec_sha256").asText());
        assertThat(ir.at("/nodes/plan/route/mode").asText()).isEqualTo("always");
        assertThat(ir.at("/nodes/review/route/domain/kind").asText()).isEqualTo("closed");
        assertThat(ir.at("/nodes/review/route/cases/1/action/kind").asText()).isEqualTo("continue_loop");
        assertThat(ir.at("/loops/0/budget/kind").asText()).isEqualTo("artifact_field");
        assertThat(ir.at("/loops/0/on_exhaust/to").asText()).isEqualTo("escalate");
    }

    @Test
    void releaseContractRejectsVersionOneSpecsAndRawEdgeRouting() throws Exception {
        Path versionOne = tempDir.resolve("version-one.json");
        Files.writeString(versionOne, """
                {
                  "version": 1,
                  "workflow_id": "version_one_test",
                  "entry_node": "start",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "start",
                      "command": ["sh", "-c", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(versionOne))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("workflow spec version must be 2");

        Path rawRoutingMode = tempDir.resolve("raw-routing-mode.json");
        Files.writeString(rawRoutingMode, """
                {
                  "version": 2,
                  "routing_mode": "raw_edges",
                  "workflow_id": "raw_routing_mode",
                  "entry_node": "start",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "start",
                      "command": ["sh", "-c", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(rawRoutingMode))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("routing_mode must be structured");

        Path topLevelEdges = tempDir.resolve("top-level-edges.json");
        Files.writeString(topLevelEdges, """
                {
                  "version": 2,
                  "workflow_id": "top_level_edges",
                  "entry_node": "start",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "start",
                      "command": ["sh", "-c", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ],
                  "edges": [
                    { "from": "start", "to": "__complete__" }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(topLevelEdges))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("uses removed top-level 'edges'");

    }

    @Test
    void toolRequirementsValidateCommandNamesGroupsAndDuplicates() throws Exception {
        Path valid = tempDir.resolve("tool-requirements.json");
        Files.writeString(valid, """
                {
                  "version": 2,
                  "tool_requirements": {
                    "all_of": ["bash", "codex"],
                    "any_of": [["jq", "python3"]]
                  },
                  "workflow_id": "tool_requirements_test",
                  "entry_node": "start",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "start",
                      "command": ["bash", "-lc", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """);

        WorkflowSpec spec = WorkflowSpecs.load(valid);
        JsonNode ir = WorkflowSpecCompiler.compile(spec).workflowIr();

        assertThat(spec.effectiveToolRequirements().allOf()).containsExactly("bash", "codex");
        assertThat(spec.effectiveToolRequirements().anyOf()).containsExactly(List.of("jq", "python3"));
        assertThat(ir.get("source_spec_sha256").asText()).hasSize(64);

        Path duplicate = tempDir.resolve("duplicate-tool-requirements.json");
        Files.writeString(duplicate, """
                {
                  "version": 2,
                  "tool_requirements": { "all_of": ["bash"], "any_of": [["bash", "python3"]] },
                  "workflow_id": "duplicate_tool_requirements",
                  "entry_node": "start",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "start",
                      "command": ["bash", "-lc", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(duplicate))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("tool_requirements command name 'bash' is duplicated");

        Path emptyGroup = tempDir.resolve("empty-tool-group.json");
        Files.writeString(emptyGroup, """
                {
                  "version": 2,
                  "tool_requirements": { "any_of": [[]] },
                  "workflow_id": "empty_tool_group",
                  "entry_node": "start",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "start",
                      "command": ["bash", "-lc", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(emptyGroup))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("tool_requirements.any_of[0] must not be empty");

        Path pathLikeCommand = tempDir.resolve("path-like-tool.json");
        Files.writeString(pathLikeCommand, """
                {
                  "version": 2,
                  "tool_requirements": { "all_of": ["bin/codex"] },
                  "workflow_id": "path_like_tool",
                  "entry_node": "start",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "start",
                      "command": ["bash", "-lc", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(pathLikeCommand))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("must use only ASCII letters");
    }

    @Test
    void structuredLoopAndRetryUnsignedIntegerFieldsMatchRustRange() throws Exception {
        Path specPath = tempDir.resolve("structured-u32.json");
        Files.writeString(specPath, structuredLoopRetrySpec("2147483648", "4294967295"));

        WorkflowSpec spec = WorkflowSpecs.load(specPath);
        JsonNode ir = WorkflowSpecCompiler.compile(spec).workflowIr();
        JsonNode secondIr = WorkflowSpecCompiler.compile(spec).workflowIr();

        assertThat(ir.at("/loops/0/budget/max_iterations").asLong()).isEqualTo(2_147_483_648L);
        assertThat(ir.get("source_spec_sha256").asText()).hasSize(64);
        assertThat(secondIr.get("source_spec_sha256").asText()).isEqualTo(ir.get("source_spec_sha256").asText());
    }

    @Test
    void structuredUnsignedIntegerValidationRejectsInvalidLoopAndRetryValues() throws Exception {
        Path tooLargeLoop = tempDir.resolve("too-large-loop.json");
        Files.writeString(tooLargeLoop, structuredLoopRetrySpec("4294967296", "1"));
        assertThatThrownBy(() -> WorkflowSpecs.load(tooLargeLoop))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("loop max_iterations must be an unsigned 32-bit integer");

        Path decimalLoop = tempDir.resolve("decimal-loop.json");
        Files.writeString(decimalLoop, structuredLoopRetrySpec("1.5", "1"));
        assertThatThrownBy(() -> WorkflowSpecs.load(decimalLoop))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("loop max_iterations must be an unsigned 32-bit integer");

        Path nullLoop = tempDir.resolve("null-loop.json");
        Files.writeString(nullLoop, structuredLoopRetrySpec("null", "1"));
        assertThatThrownBy(() -> WorkflowSpecs.load(nullLoop))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("loop max_iterations must be an unsigned 32-bit integer");

        Path tooLargeRetry = tempDir.resolve("too-large-retry.json");
        Files.writeString(tooLargeRetry, structuredLoopRetrySpec("1", "4294967296"));
        assertThatThrownBy(() -> WorkflowSpecs.load(tooLargeRetry))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("retry_policy.max_attempts must be an unsigned 32-bit integer");

        Path decimalRetry = tempDir.resolve("decimal-retry.json");
        Files.writeString(decimalRetry, structuredLoopRetrySpec("1", "1.5"));
        assertThatThrownBy(() -> WorkflowSpecs.load(decimalRetry))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("retry_policy.max_attempts must be an unsigned 32-bit integer");

        Path nullRetry = tempDir.resolve("null-retry.json");
        Files.writeString(nullRetry, structuredLoopRetrySpec("1", "null"));
        assertThatThrownBy(() -> WorkflowSpecs.load(nullRetry))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("retry_policy.max_attempts must be an unsigned 32-bit integer");

        Path negativeRetry = tempDir.resolve("negative-retry.json");
        Files.writeString(negativeRetry, structuredLoopRetrySpec("1", "-1"));
        assertThatThrownBy(() -> WorkflowSpecs.load(negativeRetry))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("retry_policy.max_attempts must be an unsigned 32-bit integer");
    }

    @Test
    void validationRejectsRepresentativeInvalidSpecShapes() throws Exception {
        Path nonObject = tempDir.resolve("non-object.json");
        Files.writeString(nonObject, "[]\n");
        assertThatThrownBy(() -> WorkflowSpecs.load(nonObject))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("workflow spec must be a JSON object");

        Path topLevelEdges = tempDir.resolve("legacy-edges.json");
        Files.writeString(topLevelEdges, """
                {
                  "version": 2,
                  "workflow_id": "legacy_edges_test",
                  "entry_node": "a",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "a",
                      "command": ["sh", "-c", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    },
                    {
                      "kind": "command",
                      "id": "b",
                      "command": ["sh", "-c", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ],
                  "edges": [
                    { "from": "a", "to": "b" },
                    { "from": "b", "to": "a" }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(topLevelEdges))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("uses removed top-level 'edges'");

        Path zeroLoop = tempDir.resolve("zero-loop.json");
        Files.writeString(zeroLoop, """
                {
                  "version": 2,
                  "workflow_id": "loop_test",
                  "entry_node": "start",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "start",
                      "command": ["sh", "-c", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ],
                  "loops": [
                    {
                      "id": "retry",
                      "controller_node": "start",
                      "entry_node": "start",
                      "budget": { "kind": "literal", "max_iterations": 0 },
                      "on_exhaust": { "to": "__escalate__" }
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(zeroLoop))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("max_iterations must be greater than zero");

        Path promptConflict = tempDir.resolve("prompt-conflict.json");
        Files.writeString(promptConflict, """
                {
                  "version": 2,
                  "workflow_id": "prompt_conflict",
                  "entry_node": "plan",
                  "agents": {
                    "planner": { "runner": "codex", "command": ["codex", "exec"] }
                  },
                  "nodes": [
                    {
                      "kind": "agent",
                      "id": "plan",
                      "agent_id": "planner",
                      "prompt_file": "missing.txt",
                      "prompt_template": "already set"
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(promptConflict))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("cannot declare both prompt_template and prompt_file");

        Path commandRunnerAgent = tempDir.resolve("command-runner-agent.json");
        Files.writeString(commandRunnerAgent, """
                {
                  "version": 2,
                  "workflow_id": "command_runner_agent",
                  "entry_node": "plan",
                  "agents": {
                    "planner": { "runner": "command", "command": ["sh", "-c", "true"] }
                  },
                  "nodes": [
                    {
                      "kind": "agent",
                      "id": "plan",
                      "agent_id": "planner",
                      "prompt_template": "Plan"
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(commandRunnerAgent))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("agents may only use 'agent' or 'codex'");

        Path negativeTimeout = tempDir.resolve("negative-timeout.json");
        Files.writeString(negativeTimeout, """
                {
                  "version": 2,
                  "workflow_id": "negative_timeout",
                  "entry_node": "plan",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "plan",
                      "timeout_ms": -1,
                      "command": ["sh", "-c", "true"]
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(negativeTimeout))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("node 'plan' timeout_ms must be a non-negative integer");

        Path stringTimeout = tempDir.resolve("string-timeout.json");
        Files.writeString(stringTimeout, """
                {
                  "version": 2,
                  "workflow_id": "string_timeout",
                  "entry_node": "plan",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "plan",
                      "timeout_ms": "1000",
                      "command": ["sh", "-c", "true"]
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(stringTimeout))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("node 'plan' timeout_ms must be a non-negative integer");
    }

    @Test
    void authoredTimeoutMsRejectsIntegersOutsideLongRange() throws Exception {
        Path maxLongTimeout = tempDir.resolve("max-long-timeout.json");
        Files.writeString(maxLongTimeout, """
                {
                  "version": 2,
                  "workflow_id": "max_long_timeout",
                  "entry_node": "plan",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "plan",
                      "timeout_ms": 9223372036854775807,
                      "command": ["sh", "-c", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """);
        assertThat(WorkflowSpecs.load(maxLongTimeout).nodes().getFirst().get("timeout_ms").asLong())
                .isEqualTo(Long.MAX_VALUE);

        Path outOfRangeTimeout = tempDir.resolve("out-of-range-timeout.json");
        Files.writeString(outOfRangeTimeout, """
                {
                  "version": 2,
                  "workflow_id": "out_of_range_timeout",
                  "entry_node": "plan",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "plan",
                      "timeout_ms": 999999999999999999999999999999,
                      "command": ["sh", "-c", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(outOfRangeTimeout))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("node 'plan' timeout_ms must be a non-negative integer");
    }

    @Test
    void interfaceDerivationAndSubrunCompatibilityValidateChildContracts() throws Exception {
        WorkflowSpec child = WorkflowSpecs.load(writeChildSpec("request"));
        WorkflowSpec parent = WorkflowSpecs.load(writeParentSubrunSpec());

        JsonNode childInterface = WorkflowSpecCompiler.compile(child).workflowInterface();
        Set<String> requiredImports = WorkflowSpecCompiler.requiredSubrunImports(
                parent,
                "child",
                childInterface,
                "request",
                Map.of("child_report", "imported_report"));

        assertThat(childInterface.at("/inputs/request/media_type").asText()).isEqualTo("application/json");
        assertThat(childInterface.at("/exports/child_report/required").asBoolean()).isTrue();
        assertThat(requiredImports).containsExactly("child_report");

        WorkflowSpec extraInputChild = WorkflowSpecs.load(writeChildSpec("request\", \"secondary"));
        assertThatThrownBy(() -> WorkflowSpecCompiler.requiredSubrunImports(
                parent,
                "child",
                WorkflowSpecCompiler.compile(extraInputChild).workflowInterface(),
                "request",
                Map.of("child_report", "imported_report")))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("required child interface inputs remain unbound: secondary");
    }

    @Test
    void compilerFreezesChildWorkflowSnapshotsIntoRunPackage() throws Exception {
        Path sourceRoot = Files.createDirectory(tempDir.resolve("compiler-source"));
        Path child = sourceRoot.resolve("child.json");
        Path parent = sourceRoot.resolve("parent.json");
        Files.writeString(child, childWorkflowSpecJson("request"));
        Files.writeString(parent, parentSubrunSpecJson());
        Path logicalRunDir = Path.of("/runs/sample");
        Path actualRunDir = tempDir.resolve("runs").resolve(".staging").resolve("sample");

        WorkflowSpecCompiler.CompiledWorkflow compiled = WorkflowSpecCompiler.compileFrozenRunPackage(
                parent,
                logicalRunDir,
                actualRunDir,
                templateId -> {
                    throw new AssertionError("template resolver should not be used");
                });

        String frozenChildPath = compiled.spec().nodes().getFirst().at("/workflow_ref/path").asText();
        Path frozenChildSpec = actualRunDir.resolve(frozenChildPath).normalize();
        assertThat(frozenChildPath).contains("subruns/frozen-workflows/");
        assertThat(Files.isRegularFile(frozenChildSpec)).isTrue();
        assertThat(Files.isRegularFile(frozenChildSpec.getParent().resolve("workflow-ir.json"))).isTrue();
        assertThat(Files.isRegularFile(frozenChildSpec.getParent().resolve("workflow-interface.json"))).isTrue();
        assertThat(compiled.workflowIr().get("source_spec_sha256").asText()).hasSize(64);
        assertThat(compiled.sourceSpecSha256()).isEqualTo(compiled.workflowIr().get("source_spec_sha256").asText());
    }

    @Test
    void compilerFreezesTemplateSubrunSnapshotsAndRunLocalPaths() throws Exception {
        Path parentRoot = Files.createDirectory(tempDir.resolve("parent-source"));
        Path templateRoot = Files.createDirectory(tempDir.resolve("template-source"));
        Path parent = parentRoot.resolve("parent.json");
        Path child = templateRoot.resolve("workflow.json");
        Files.writeString(parent, parentTemplateSubrunSpecJson());
        Files.writeString(child, childTemplateWorkflowSpecJson());
        Path logicalRunDir = Path.of("/runs/template-parent");
        Path actualRunDir = tempDir.resolve("runs").resolve(".staging").resolve("template-parent");
        boolean[] resolvedTemplate = {false};

        WorkflowSpecCompiler.CompiledWorkflow compiled = WorkflowSpecCompiler.compileFrozenRunPackage(
                parent,
                logicalRunDir,
                actualRunDir,
                templateId -> {
                    assertThat(templateId).isEqualTo("child-template");
                    resolvedTemplate[0] = true;
                    return new WorkflowSpecCompiler.ResolvedWorkflow(
                            WorkflowSpecs.load(child),
                            templateRoot,
                            child,
                            "template:" + templateId);
                });

        JsonNode frozenParentNotifications = compiled.spec().notifications();
        assertThat(resolvedTemplate[0]).isTrue();
        assertThat(frozenParentNotifications.at("/default_hook/path").asText())
                .isEqualTo(parentRoot.resolve("hooks/default.sh").normalize().toString());
        assertThat(frozenParentNotifications.at("/default_hook/cwd").asText())
                .isEqualTo(parentRoot.resolve("parent-hooks").normalize().toString());

        String frozenChildPath = compiled.spec().nodes().getFirst().at("/workflow_ref/path").asText();
        Path frozenChildSpecPath = actualRunDir.resolve(frozenChildPath).normalize();
        WorkflowSpec frozenChild = WorkflowSpecs.load(frozenChildSpecPath);
        assertThat(frozenChildPath).contains("subruns/frozen-workflows/template-child-template-");
        assertThat(Files.isRegularFile(frozenChildSpecPath.getParent().resolve("source/workflow.json"))).isTrue();
        assertThat(frozenChild.agents().at("/planner/cwd").asText())
                .isEqualTo(templateRoot.resolve("agent-work").normalize().toString());
        assertThat(frozenChild.nodes().getFirst().get("cwd").asText())
                .isEqualTo(templateRoot.resolve("child-work").normalize().toString());
        assertThat(frozenChild.notifications().at("/complete_hook/path").asText())
                .isEqualTo(templateRoot.resolve("hooks/complete.sh").normalize().toString());
        assertThat(frozenChild.notifications().at("/complete_hook/cwd").asText())
                .isEqualTo(templateRoot.resolve("child-hooks").normalize().toString());
        assertThat(frozenChild.notifications().at("/human_review_hook/cwd").asText())
                .isEqualTo("$request.repo_root");
    }

    @Test
    void subrunCompatibilityRejectsMissingChildRequestsAndMediaTypeMismatches() throws Exception {
        WorkflowSpec child = WorkflowSpecs.load(writeChildSpec("request"));
        WorkflowSpec parent = WorkflowSpecs.load(writeParentSubrunSpec());

        ObjectNode missingRequest = WorkflowSpecCompiler.compile(child).workflowInterface().deepCopy();
        ((ObjectNode) missingRequest.get("inputs")).remove("request");
        assertThatThrownBy(() -> WorkflowSpecCompiler.requiredSubrunImports(
                parent,
                "child",
                missingRequest,
                "request",
                Map.of("child_report", "imported_report")))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("request_artifact 'request' is not declared by child workflow");

        JsonNode childInterface = WorkflowSpecCompiler.compile(child).workflowInterface();
        assertThatThrownBy(() -> WorkflowSpecCompiler.requiredSubrunImports(
                parent,
                "child",
                childInterface,
                "unknown_request",
                Map.of("child_report", "imported_report")))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("references unknown parent request_artifact 'unknown_request'");

        assertThatThrownBy(() -> WorkflowSpecCompiler.requiredSubrunImports(
                parent,
                "child",
                childInterface,
                "request",
                Map.of("child_report", "unknown_parent_artifact")))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("imports child artifact 'child_report' into unknown parent artifact");

        assertThatThrownBy(() -> WorkflowSpecCompiler.requiredSubrunImports(
                parent,
                "child",
                childInterface,
                "request",
                Map.of("unknown_child_artifact", "imported_report")))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("imports child artifact 'unknown_child_artifact' that is not declared");

        ObjectNode nonStringRequestMediaType = childInterface.deepCopy();
        ((ObjectNode) nonStringRequestMediaType.at("/inputs/request")).put("media_type", 7);
        assertThatThrownBy(() -> WorkflowSpecCompiler.requiredSubrunImports(
                parent,
                "child",
                nonStringRequestMediaType,
                "request",
                Map.of("child_report", "imported_report")))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("child request media_type must be a string");

        ObjectNode mismatchedRequestMediaType = WorkflowSpecCompiler.compile(child).workflowInterface().deepCopy();
        ((ObjectNode) mismatchedRequestMediaType.at("/inputs/request")).put("media_type", "text/plain");
        assertThatThrownBy(() -> WorkflowSpecCompiler.requiredSubrunImports(
                parent,
                "child",
                mismatchedRequestMediaType,
                "request",
                Map.of("child_report", "imported_report")))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("does not match child workflow interface input media type");

        ObjectNode mismatchedExportMediaType = WorkflowSpecCompiler.compile(child).workflowInterface().deepCopy();
        ((ObjectNode) mismatchedExportMediaType.at("/exports/child_report")).put("media_type", "application/json");
        assertThatThrownBy(() -> WorkflowSpecCompiler.requiredSubrunImports(
                parent,
                "child",
                mismatchedExportMediaType,
                "request",
                Map.of("child_report", "imported_report")))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("does not match child interface export");
    }

    @Test
    void structuredValidationRejectsOpenRoutesWithoutDefaultsAndNonJsonLoopBudgets() throws Exception {
        Path openRoute = tempDir.resolve("open-route.json");
        Files.writeString(openRoute, """
                {
                  "version": 2,
                  "workflow_id": "open_route",
                  "entry_node": "review",
                  "nodes": [
                    {
                      "kind": "human",
                      "id": "review",
                      "fields": [
                        { "name": "decision", "required": true, "kind": "string" }
                      ],
                      "route": {
                        "mode": "by_field",
                        "field": "decision",
                        "cases": [
                          { "equals": "accept", "to": "__complete__" }
                        ]
                      }
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(openRoute))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("must declare a default route");

        Path textBudget = tempDir.resolve("text-budget.json");
        Files.writeString(textBudget, """
                {
                  "version": 2,
                  "workflow_id": "text_budget",
                  "entry_node": "start",
                  "artifacts": {
                    "policy": { "path": "artifacts/policy.md", "media_type": "text/markdown" }
                  },
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "start",
                      "outputs": ["policy"],
                      "command": ["sh", "-c", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ],
                  "loops": [
                    {
                      "id": "retry",
                      "controller_node": "start",
                      "entry_node": "start",
                      "budget": { "kind": "artifact_field", "artifact": "policy", "field": "max_iterations" },
                      "on_exhaust": { "to": "__escalate__" }
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> WorkflowSpecs.load(textBudget))
                .isInstanceOf(ForgeException.class)
                .hasMessageContaining("must use a JSON media type");
    }

    private String structuredWorkflowWithExternalFiles(String extraCommandField) {
        return """
                {
                  "version": 2,
                  "workflow_id": "external_files",
                  "entry_node": "plan",
                  "agents": {
                    "planner": {
                      "runner": "codex",
                      "command": ["codex", "exec"],
                      "prompt_delivery": "stdin_file"
                    }
                  },
                  "artifacts": {
                    "request": { "path": "artifacts/request.json", "media_type": "application/json" },
                    "plan": { "path": "artifacts/plan.md", "media_type": "text/markdown" },
                    "report": { "path": "artifacts/report.md", "media_type": "text/markdown" }
                  },
                  "nodes": [
                    {
                      "kind": "agent",
                      "id": "plan",
                      "agent_id": "planner",
                      "inputs": ["request"],
                      "outputs": ["plan"],
                      "prompt_file": "prompts/plan.txt",
                      "route": { "mode": "always", "to": "publish" }
                    },
                    {
                      "kind": "command",
                      "id": "publish",
                      "inputs": ["plan"],
                      "outputs": ["report"],
                      %s
                      "command_file": "scripts/publish.sh",
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """.formatted(extraCommandField);
    }

    private static String structuredLoopRetrySpec(String maxIterations, String maxAttempts) {
        return """
                {
                  "version": 2,
                  "workflow_id": "structured_u32_test",
                  "entry_node": "plan",
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "plan",
                      "command": ["sh", "-c", "true"],
                      "retry_policy": { "max_attempts": %s },
                      "route": { "mode": "always", "to": "review" }
                    },
                    {
                      "kind": "human",
                      "id": "review",
                      "fields": [
                        {
                          "name": "status",
                          "required": true,
                          "kind": "enum",
                          "allowed_values": ["done", "again"]
                        }
                      ],
                      "route": {
                        "mode": "by_field",
                        "field": "status",
                        "cases": [
                          { "equals": "done", "to": "__complete__" },
                          { "equals": "again", "continue_loop": "review_loop" }
                        ]
                      }
                    }
                  ],
                  "loops": [
                    {
                      "id": "review_loop",
                      "controller_node": "review",
                      "entry_node": "plan",
                      "budget": { "kind": "literal", "max_iterations": %s },
                      "on_exhaust": { "to": "__escalate__" }
                    }
                  ]
                }
                """.formatted(maxAttempts, maxIterations);
    }

    private Path writeChildSpec(String inputs) throws Exception {
        Path path = tempDir.resolve("child-" + Integer.toHexString(inputs.hashCode()) + ".json");
        Files.writeString(path, childWorkflowSpecJson(inputs));
        return path;
    }

    private static String childWorkflowSpecJson(String inputs) {
        return """
                {
                  "version": 2,
                  "workflow_id": "child_workflow",
                  "entry_node": "write_report",
                  "interface": {
                    "inputs": ["%s"],
                    "exports": {
                      "child_report": { "required": true, "terminal_only": true }
                    }
                  },
                  "artifacts": {
                    "request": { "path": "artifacts/request.json", "media_type": "application/json" },
                    "secondary": { "path": "artifacts/secondary.json", "media_type": "application/json" },
                    "child_report": { "path": "artifacts/child_report.md", "media_type": "text/markdown" }
                  },
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "write_report",
                      "inputs": ["request"],
                      "outputs": ["child_report"],
                      "command": ["sh", "-c", "true"],
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """.formatted(inputs);
    }

    private Path writeParentSubrunSpec() throws Exception {
        Path path = tempDir.resolve("parent.json");
        Files.writeString(path, parentSubrunSpecJson());
        return path;
    }

    private static String parentSubrunSpecJson() {
        return """
                {
                  "version": 2,
                  "workflow_id": "parent_workflow",
                  "entry_node": "child",
                  "artifacts": {
                    "request": { "path": "artifacts/request.json", "media_type": "application/json" },
                    "summary": { "path": "artifacts/summary.md", "media_type": "text/markdown" },
                    "imported_report": { "path": "artifacts/imported_report.md", "media_type": "text/markdown" }
                  },
                  "nodes": [
                    {
                      "kind": "subrun",
                      "id": "child",
                      "inputs": ["request"],
                      "outputs": ["summary", "imported_report"],
                      "workflow_ref": { "path": "child.json" },
                      "request_artifact": "request",
                      "summary_artifact": "summary",
                      "import_artifacts": { "child_report": "imported_report" },
                      "route": {
                        "mode": "by_field",
                        "field": "subrun_status",
                        "cases": [
                          { "equals": "complete", "to": "__complete__" },
                          { "equals": "escalate", "to": "__escalate__" },
                          { "equals": "failed", "to": "__escalate__" }
                        ]
                      }
                    }
                  ]
                }
                """;
    }

    private static String parentTemplateSubrunSpecJson() {
        return """
                {
                  "version": 2,
                  "workflow_id": "parent_template_workflow",
                  "entry_node": "child",
                  "notifications": {
                    "default_hook": { "path": "hooks/default.sh", "cwd": "parent-hooks" }
                  },
                  "artifacts": {
                    "request": { "path": "artifacts/request.json", "media_type": "application/json" },
                    "summary": { "path": "artifacts/summary.md", "media_type": "text/markdown" },
                    "imported_report": { "path": "artifacts/imported_report.md", "media_type": "text/markdown" }
                  },
                  "nodes": [
                    {
                      "kind": "subrun",
                      "id": "child",
                      "inputs": ["request"],
                      "outputs": ["summary", "imported_report"],
                      "workflow_ref": { "template_id": "child-template" },
                      "request_artifact": "request",
                      "summary_artifact": "summary",
                      "import_artifacts": { "child_report": "imported_report" },
                      "route": {
                        "mode": "by_field",
                        "field": "subrun_status",
                        "cases": [
                          { "equals": "complete", "to": "__complete__" },
                          { "equals": "escalate", "to": "__escalate__" },
                          { "equals": "failed", "to": "__escalate__" }
                        ]
                      }
                    }
                  ]
                }
                """;
    }

    private static String childTemplateWorkflowSpecJson() {
        return """
                {
                  "version": 2,
                  "workflow_id": "child_template_workflow",
                  "entry_node": "write_report",
                  "interface": {
                    "inputs": ["request"],
                    "exports": {
                      "child_report": { "required": true, "terminal_only": true }
                    }
                  },
                  "agents": {
                    "planner": { "runner": "codex", "command": ["codex", "exec"], "cwd": "agent-work" }
                  },
                  "notifications": {
                    "complete_hook": { "path": "hooks/complete.sh", "cwd": "child-hooks" },
                    "human_review_hook": { "path": "hooks/review.sh", "cwd": "$request.repo_root" }
                  },
                  "artifacts": {
                    "request": { "path": "artifacts/request.json", "media_type": "application/json" },
                    "child_report": { "path": "artifacts/child_report.md", "media_type": "text/markdown" }
                  },
                  "nodes": [
                    {
                      "kind": "command",
                      "id": "write_report",
                      "inputs": ["request"],
                      "outputs": ["child_report"],
                      "command": ["sh", "-c", "true"],
                      "cwd": "child-work",
                      "route": { "mode": "always", "to": "__complete__" }
                    }
                  ]
                }
                """;
    }
}
