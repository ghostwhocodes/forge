package dev.llaith.forge.template.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.llaith.forge.ForgeException;
import dev.llaith.forge.runtime.run.RunSlug;
import dev.llaith.forge.storage.ArtifactStore;
import dev.llaith.forge.storage.EventLog;
import dev.llaith.forge.template.TemplateBundle;
import dev.llaith.forge.util.Filesystem;
import dev.llaith.forge.util.Json;
import dev.llaith.forge.workflow.event.EventEnvelope;
import dev.llaith.forge.workflow.reducer.WorkflowReducer;
import dev.llaith.forge.workflow.state.ArtifactRecord;
import dev.llaith.forge.workflow.state.DerivedRunState;
import dev.llaith.forge.workflow.state.RunStatus;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class TemplateAnalysis {
    private static final long UNSIGNED_INT_MAX = 4_294_967_295L;

    private TemplateAnalysis() {
    }

    public static TemplateRunAnalysisResult analyzeRuns(
            TemplateBundle bundle,
            String source,
            Path runsDir,
            List<String> selectedSlugs,
            @Nullable Path outDir
    ) {
        SourceWorkflowMap sourceMap = loadSourceWorkflowMap(bundle);
        List<CandidateRun> candidates = collectCandidateRuns(runsDir, selectedSlugs);
        List<RunMetrics> analyzedRuns = new ArrayList<>();
        int skippedRunCount = 0;

        for (CandidateRun candidate : candidates) {
            Path specPath = candidate.runDir().resolve("spec.json");
            Path eventsPath = candidate.runDir().resolve("events.ndjson");
            if (!Files.isRegularFile(specPath) || !Files.isRegularFile(eventsPath)) {
                throw new ForgeException("run '" + candidate.slug() + "' is missing spec.json or events.ndjson under "
                        + candidate.runDir());
            }
            JsonNode spec = Json.read(specPath, JsonNode.class);
            if (!bundle.workflow().workflowId().equals(text(spec, "workflow_id"))) {
                skippedRunCount++;
                continue;
            }
            analyzedRuns.add(analyzeSingleRun(bundle, candidate.runDir(), candidate.slug(), EventLog.readEvents(eventsPath)));
        }

        if (analyzedRuns.isEmpty()) {
            throw new ForgeException("no matching runs found for workflow '"
                    + bundle.workflow().workflowId()
                    + "' under "
                    + runsDir);
        }

        Map<String, NodeInfo> workflowNodes = workflowNodes(bundle);
        Map<String, NodeAggregate> nodeAggregates = new TreeMap<>();
        Map<ArtifactFindingKey, ArtifactAggregate> artifactAggregates = new TreeMap<>();
        int completedRuns = 0;
        int escalatedRuns = 0;
        int failedRuns = 0;
        int pendingRuns = 0;
        int totalEvents = 0;
        int totalDispatchFailures = 0;
        int totalHumanReworkSignals = 0;

        for (RunMetrics run : analyzedRuns) {
            totalEvents += run.eventCount();
            switch (run.status()) {
                case "completed" -> completedRuns++;
                case "escalated" -> escalatedRuns++;
                case "failed" -> failedRuns++;
                default -> pendingRuns++;
            }

            for (Map.Entry<String, Integer> attempt : run.nodeAttempts().entrySet()) {
                NodeAggregate aggregate = aggregateFor(nodeAggregates, workflowNodes, sourceMap, bundle, attempt.getKey());
                aggregate.runSlugs.add(run.slug());
                aggregate.totalAttempts += attempt.getValue();
            }
            for (Map.Entry<String, Integer> failure : run.nodeFailures().entrySet()) {
                totalDispatchFailures += failure.getValue();
                NodeAggregate aggregate = aggregateFor(nodeAggregates, workflowNodes, sourceMap, bundle, failure.getKey());
                aggregate.runSlugs.add(run.slug());
                aggregate.dispatchFailures += failure.getValue();
            }
            for (Map.Entry<String, Integer> signal : run.nodeHumanReworkSignals().entrySet()) {
                totalHumanReworkSignals += signal.getValue();
                NodeAggregate aggregate = aggregateFor(nodeAggregates, workflowNodes, sourceMap, bundle, signal.getKey());
                aggregate.runSlugs.add(run.slug());
                aggregate.humanReworkSignals += signal.getValue();
            }
            for (Map.Entry<String, Integer> loopback : run.nodeLoopbacks().entrySet()) {
                NodeAggregate aggregate = aggregateFor(nodeAggregates, workflowNodes, sourceMap, bundle, loopback.getKey());
                aggregate.runSlugs.add(run.slug());
                aggregate.loopbacks += loopback.getValue();
            }
            if (run.escalationNode() != null && workflowNodes.containsKey(run.escalationNode())) {
                NodeAggregate aggregate = aggregateFor(nodeAggregates, workflowNodes, sourceMap, bundle, run.escalationNode());
                aggregate.runSlugs.add(run.slug());
                aggregate.escalations++;
            }
            for (ArtifactIssueOccurrence issue : run.artifactIssues()) {
                List<String> targetFiles = issue.producerNode() == null
                        ? List.of(bundle.workflowPath().toString())
                        : targetFilesForNode(workflowNodes.get(issue.producerNode()), sourceMap, bundle);
                ArtifactFindingKey key = new ArtifactFindingKey(issue.artifactName(), issue.issueType());
                ArtifactAggregate aggregate = artifactAggregates.computeIfAbsent(key, ignored -> new ArtifactAggregate(
                        issue.producerNode(),
                        issue.mediaType(),
                        targetFiles.isEmpty() ? bundle.workflowPath().toString() : targetFiles.getFirst()));
                aggregate.count++;
                aggregate.details.add(issue.detail());
                aggregate.runSlugs.add(run.slug());
            }
        }

        int analyzedRunCount = analyzedRuns.size();
        List<NodeMetric> nodeMetrics = buildNodeMetrics(nodeAggregates, analyzedRunCount);
        List<ArtifactFinding> artifactFindings = buildArtifactFindings(artifactAggregates);
        List<ImprovementSuggestion> suggestions = buildSuggestions(
                nodeMetrics,
                artifactFindings,
                analyzedRunCount,
                bundle.workflowPath());
        TemplateRunAnalysisResult result = new TemplateRunAnalysisResult(
                "ok",
                source,
                bundle.manifest().id(),
                bundle.workflow().workflowId(),
                bundle.workflow().effectiveRoutingMode(),
                bundle.workflow().routingModeSource(),
                bundle.workflow().routingModeWarnings(),
                runsDir.toString(),
                analyzedRunCount,
                skippedRunCount,
                analyzedRuns.stream().map(RunMetrics::slug).toList(),
                new AnalysisSummary(
                        completedRuns,
                        escalatedRuns,
                        failedRuns,
                        pendingRuns,
                        totalEvents,
                        totalDispatchFailures,
                        totalHumanReworkSignals,
                        artifactFindings.size()),
                nodeMetrics,
                artifactFindings,
                suggestions,
                structuredWorkflowSummary(bundle),
                null);

        if (outDir == null) {
            return result;
        }
        EmittedArtifacts emitted = writeAnalysisArtifacts(result, outDir);
        return result.withEmittedArtifacts(emitted);
    }

    public static TemplateUpdateProposalResult proposeUpdate(
            TemplateBundle bundle,
            String source,
            Path runsDir,
            List<String> selectedSlugs,
            Path outDir
    ) {
        TemplateRunAnalysisResult analysis = analyzeRuns(bundle, source, runsDir, selectedSlugs, null);
        List<FileProposal> fileProposals = buildFileProposals(bundle, analysis);
        ProposalArtifacts artifacts = writeProposalArtifacts(bundle, analysis, fileProposals, outDir);
        return new TemplateUpdateProposalResult(
                "ok",
                analysis.source(),
                analysis.templateId(),
                analysis.workflowId(),
                analysis.runsDir(),
                analysis.analyzedRunCount(),
                analysis.suggestions().size(),
                fileProposals.size(),
                artifacts);
    }

    private static List<CandidateRun> collectCandidateRuns(Path runsDir, List<String> selectedSlugs) {
        if (!Files.isDirectory(runsDir)) {
            throw new ForgeException("runs directory does not exist: " + runsDir);
        }
        if (!selectedSlugs.isEmpty()) {
            List<CandidateRun> runs = new ArrayList<>();
            for (String slug : selectedSlugs) {
                RunSlug.parse(slug);
                Path runDir = runsDir.resolve(slug);
                if (!Files.isDirectory(runDir)) {
                    throw new ForgeException("run '" + slug + "' does not exist under " + runsDir);
                }
                runs.add(new CandidateRun(slug, runDir));
            }
            return List.copyOf(runs);
        }
        try (Stream<Path> stream = Files.list(runsDir)) {
            List<CandidateRun> runs = new ArrayList<>();
            for (Path path : stream.sorted(Comparator.comparing(Path::toString)).toList()) {
                Path fileName = path.getFileName();
                if (fileName == null || !Files.isDirectory(path)) {
                    continue;
                }
                String slug = fileName.toString();
                if (!isValidRunSlug(slug)
                        || !Files.isRegularFile(path.resolve("spec.json"))
                        || !Files.isRegularFile(path.resolve("events.ndjson"))) {
                    continue;
                }
                runs.add(new CandidateRun(slug, path));
            }
            return List.copyOf(runs);
        } catch (IOException error) {
            throw new ForgeException("failed to read runs directory " + runsDir, error);
        }
    }

    private static boolean isValidRunSlug(String slug) {
        try {
            RunSlug.parse(slug);
            return true;
        } catch (ForgeException ignored) {
            return false;
        }
    }

    private static RunMetrics analyzeSingleRun(
            TemplateBundle bundle,
            Path runDir,
            String slug,
            List<EventEnvelope> events
    ) {
        DerivedRunState state = WorkflowReducer.deriveState(events);
        Map<String, NodeInfo> workflowNodes = workflowNodes(bundle);
        Map<String, Integer> nodeAttempts = new TreeMap<>();
        Map<String, Integer> nodeFailures = new TreeMap<>();
        Map<String, Integer> humanReworkSignals = new TreeMap<>();
        Map<String, Integer> nodeLoopbacks = new TreeMap<>();
        Set<String> visitedNodes = new TreeSet<>();
        List<ArtifactIssueOccurrence> artifactIssues = new ArrayList<>();
        String escalationNode = null;

        for (EventEnvelope event : events) {
            switch (event.type()) {
                case "node_entered" -> {
                    String nodeId = requiredText(event, "node_id");
                    nodeAttempts.merge(nodeId, 1, Integer::sum);
                    visitedNodes.add(nodeId);
                }
                case "node_failed" -> nodeFailures.merge(requiredText(event, "node_id"), 1, Integer::sum);
                case "human_input_recorded" -> {
                    String nodeId = requiredText(event, "node_id");
                    JsonNode fields = event.field("fields");
                    if (fields != null && fields.isObject() && containsReworkSignal(fields)) {
                        humanReworkSignals.merge(nodeId, 1, Integer::sum);
                    }
                }
                case "transition_taken" -> {
                    String from = requiredText(event, "from");
                    String to = requiredText(event, "to");
                    if (!to.startsWith("__") && visitedNodes.contains(to)) {
                        NodeInfo fromNode = workflowNodes.get(from);
                        if (fromNode != null) {
                            nodeLoopbacks.merge(from, 1, Integer::sum);
                            if ("human".equals(fromNode.kind())) {
                                humanReworkSignals.merge(from, 1, Integer::sum);
                            }
                        }
                    }
                }
                case "run_escalated" -> escalationNode = event.textField("node_id");
                default -> {
                }
            }
        }

        for (ArtifactRecord artifact : state.artifactIndex().values()) {
            if (artifact.producerNode() == null) {
                continue;
            }
            Path resolved = ArtifactStore.resolveArtifactPath(runDir, state, artifact);
            if (!Files.isRegularFile(resolved)) {
                artifactIssues.add(new ArtifactIssueOccurrence(
                        artifact.name(),
                        artifact.producerNode(),
                        artifact.mediaType(),
                        "missing_output",
                        "expected artifact file is missing at " + resolved));
                continue;
            }
            String body = ArtifactStore.readArtifactText(runDir, state, artifact);
            if (body.trim().isEmpty()) {
                artifactIssues.add(new ArtifactIssueOccurrence(
                        artifact.name(),
                        artifact.producerNode(),
                        artifact.mediaType(),
                        "empty_output",
                        "artifact file is empty at " + resolved));
                continue;
            }
            if ("application/json".equals(artifact.mediaType()) && !isJson(body)) {
                artifactIssues.add(new ArtifactIssueOccurrence(
                        artifact.name(),
                        artifact.producerNode(),
                        artifact.mediaType(),
                        "invalid_json",
                        "artifact file does not contain valid JSON at " + resolved));
            }
        }

        return new RunMetrics(
                slug,
                runStatusLabel(state.runStatus()),
                events.size(),
                escalationNode,
                nodeAttempts,
                nodeFailures,
                humanReworkSignals,
                nodeLoopbacks,
                artifactIssues);
    }

    private static boolean containsReworkSignal(JsonNode fields) {
        return fields.properties().stream()
                .map(entry -> entry.getValue().asText(""))
                .anyMatch(TemplateAnalysis::isReworkSignal);
    }

    private static boolean isReworkSignal(String value) {
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("rework")
                || normalized.contains("changes_requested")
                || normalized.contains("change_requested")
                || normalized.contains("retry")
                || normalized.contains("rescan")
                || normalized.contains("replan")
                || normalized.contains("reject")
                || normalized.contains("rejected");
    }

    private static boolean isJson(String body) {
        try {
            Json.mapper().readTree(body);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static NodeAggregate aggregateFor(
            Map<String, NodeAggregate> nodeAggregates,
            Map<String, NodeInfo> workflowNodes,
            SourceWorkflowMap sourceMap,
            TemplateBundle bundle,
            String nodeId
    ) {
        NodeInfo node = workflowNodes.get(nodeId);
        if (node == null) {
            throw new ForgeException("node '" + nodeId + "' from run is missing from template workflow");
        }
        return nodeAggregates.computeIfAbsent(nodeId, ignored -> new NodeAggregate(
                node.kind(),
                node.configuredMaxAttempts(),
                targetFilesForNode(node, sourceMap, bundle)));
    }

    private static List<NodeMetric> buildNodeMetrics(Map<String, NodeAggregate> aggregates, int analyzedRunCount) {
        List<NodeMetric> metrics = new ArrayList<>();
        for (Map.Entry<String, NodeAggregate> entry : aggregates.entrySet()) {
            NodeAggregate aggregate = entry.getValue();
            int runCount = aggregate.runSlugs.size();
            int extraAttempts = Math.max(0, aggregate.totalAttempts - runCount);
            metrics.add(new NodeMetric(
                    entry.getKey(),
                    aggregate.kind,
                    aggregate.targetFiles.stream().sorted().distinct().toList(),
                    aggregate.configuredMaxAttempts,
                    Math.min(analyzedRunCount, runCount),
                    aggregate.totalAttempts,
                    extraAttempts,
                    aggregate.dispatchFailures,
                    aggregate.escalations,
                    aggregate.humanReworkSignals,
                    aggregate.loopbacks,
                    List.copyOf(aggregate.runSlugs)));
        }
        return List.copyOf(metrics);
    }

    private static List<ArtifactFinding> buildArtifactFindings(Map<ArtifactFindingKey, ArtifactAggregate> aggregates) {
        List<ArtifactFinding> findings = new ArrayList<>();
        for (Map.Entry<ArtifactFindingKey, ArtifactAggregate> entry : aggregates.entrySet()) {
            ArtifactAggregate aggregate = entry.getValue();
            findings.add(new ArtifactFinding(
                    entry.getKey().artifactName(),
                    aggregate.producerNode,
                    entry.getKey().issueType(),
                    aggregate.mediaType,
                    aggregate.targetFile,
                    aggregate.count,
                    List.copyOf(aggregate.runSlugs),
                    String.join("; ", aggregate.details)));
        }
        return List.copyOf(findings);
    }

    private static List<ImprovementSuggestion> buildSuggestions(
            List<NodeMetric> nodeMetrics,
            List<ArtifactFinding> artifactFindings,
            int analyzedRunCount,
            Path workflowPath
    ) {
        List<ImprovementSuggestion> suggestions = new ArrayList<>();
        for (NodeMetric node : nodeMetrics) {
            if ("human".equals(node.kind()) && (node.humanReworkSignals() > 0 || node.loopbacks() > 0)) {
                suggestions.add(new ImprovementSuggestion(
                        "human-instruction-" + node.nodeId(),
                        "human_instruction",
                        suggestionPriority(node.escalations() > 0, true),
                        workflowPath.toString(),
                        node.nodeId(),
                        "Refine the human review contract for node '" + node.nodeId() + "'",
                        "Node '" + node.nodeId() + "' produced " + node.humanReworkSignals()
                                + " human rework signal(s) and " + node.loopbacks()
                                + " loopback transition(s) across " + analyzedRunCount + " run(s).",
                        "Edit the human node '" + node.nodeId()
                                + "' in workflow.json to tighten fields, allowed values, and instructions so reviewers can approve or request rework with less ambiguity.",
                        node.runSlugs()));
            }
            if (("agent".equals(node.kind()) || "judge".equals(node.kind()))
                    && (node.extraAttempts() > 0 || node.escalations() > 0 || node.loopbacks() > 0)) {
                String targetFile = node.targetFiles().isEmpty() ? workflowPath.toString() : node.targetFiles().getFirst();
                suggestions.add(new ImprovementSuggestion(
                        "prompt-edit-" + node.nodeId(),
                        "prompt_edit",
                        suggestionPriority(node.escalations() > 0, node.extraAttempts() > 0),
                        targetFile,
                        node.nodeId(),
                        "Clarify prompt guidance for node '" + node.nodeId() + "'",
                        "Node '" + node.nodeId() + "' accumulated " + node.extraAttempts()
                                + " extra attempt(s), " + node.escalations()
                                + " escalation(s), and " + node.loopbacks() + " loopback transition(s).",
                        "Edit " + targetFile + " to restate success criteria, required artifact shape, and the conditions that should trigger escalation or human review for node '" + node.nodeId() + "'.",
                        node.runSlugs()));
            }
            if ("command".equals(node.kind()) && node.dispatchFailures() > 0) {
                String targetFile = node.targetFiles().isEmpty() ? workflowPath.toString() : node.targetFiles().getFirst();
                suggestions.add(new ImprovementSuggestion(
                        "script-edit-" + node.nodeId(),
                        "script_edit",
                        suggestionPriority(node.escalations() > 0, false),
                        targetFile,
                        node.nodeId(),
                        "Tighten helper script behavior for node '" + node.nodeId() + "'",
                        "Command node '" + node.nodeId() + "' failed " + node.dispatchFailures()
                                + " time(s) across the analyzed runs.",
                        "Edit " + targetFile + " to validate inputs earlier, emit clearer failure signals, and keep the declared output artifact contract for node '" + node.nodeId() + "'.",
                        node.runSlugs()));
                if (node.configuredMaxAttempts() <= 1L) {
                    suggestions.add(new ImprovementSuggestion(
                            "retry-policy-" + node.nodeId(),
                            "retry_policy",
                            suggestionPriority(node.escalations() > 0, false),
                            workflowPath.toString(),
                            node.nodeId(),
                            "Review retry budget for node '" + node.nodeId() + "'",
                            "Node '" + node.nodeId() + "' failed " + node.dispatchFailures()
                                    + " time(s) while workflow.json currently allows max_attempts="
                                    + node.configuredMaxAttempts() + ".",
                            "Edit workflow.json to confirm whether node '" + node.nodeId()
                                    + "' should keep max_attempts=" + node.configuredMaxAttempts()
                                    + " or allow an additional retry before escalation.",
                            node.runSlugs()));
                }
            }
        }
        for (ArtifactFinding finding : artifactFindings) {
            suggestions.add(new ImprovementSuggestion(
                    "artifact-" + finding.artifactName() + "-" + finding.issueType(),
                    "artifact_contract",
                    suggestionPriority("invalid_json".equals(finding.issueType()), false),
                    finding.targetFile(),
                    finding.producerNode(),
                    "Tighten output contract for artifact '" + finding.artifactName() + "'",
                    "Artifact '" + finding.artifactName() + "' hit '" + finding.issueType() + "' "
                            + finding.count() + " time(s): " + finding.details() + ".",
                    "Edit " + finding.targetFile() + " so the producer for '" + finding.artifactName()
                            + "' emits the declared media type consistently and document that requirement in the associated prompt or workflow instructions.",
                    finding.runSlugs()));
        }
        suggestions.sort(Comparator.comparing(ImprovementSuggestion::id));
        return List.copyOf(suggestions);
    }

    private static String suggestionPriority(boolean high, boolean medium) {
        if (high) {
            return "high";
        }
        return medium ? "medium" : "low";
    }

    private static SourceWorkflowMap loadSourceWorkflowMap(TemplateBundle bundle) {
        JsonNode workflow = Json.read(bundle.workflowPath(), JsonNode.class);
        Map<String, NodeSourceFiles> nodeSources = new TreeMap<>();
        JsonNode nodes = workflow.get("nodes");
        if (nodes != null && nodes.isArray()) {
            for (JsonNode node : nodes) {
                String id = text(node, "id");
                if (id == null) {
                    continue;
                }
                nodeSources.put(id, new NodeSourceFiles(text(node, "prompt_file"), text(node, "command_file")));
            }
        }
        return new SourceWorkflowMap(nodeSources);
    }

    private static Map<String, NodeInfo> workflowNodes(TemplateBundle bundle) {
        Map<String, NodeInfo> nodes = new TreeMap<>();
        for (JsonNode node : bundle.workflow().nodes()) {
            String id = text(node, "id");
            if (id != null) {
                nodes.put(id, new NodeInfo(
                        id,
                        textOr(node, "kind", "unknown"),
                        maxAttempts(node),
                        node));
            }
        }
        return nodes;
    }

    private static long maxAttempts(JsonNode node) {
        JsonNode retry = node.get("retry_policy");
        if (retry != null && retry.has("max_attempts")) {
            return retry.get("max_attempts").asLong(1L);
        }
        return 1L;
    }

    private static List<String> targetFilesForNode(
            @Nullable NodeInfo node,
            SourceWorkflowMap sourceMap,
            TemplateBundle bundle
    ) {
        if (node == null) {
            return List.of(bundle.workflowPath().toString());
        }
        NodeSourceFiles sourceFiles = sourceMap.nodeSources().get(node.id());
        if (sourceFiles != null
                && ("agent".equals(node.kind()) || "judge".equals(node.kind()))
                && sourceFiles.promptFile() != null) {
            return List.of(bundle.rootDir().resolve(sourceFiles.promptFile()).toString());
        }
        if (sourceFiles != null && "command".equals(node.kind()) && sourceFiles.commandFile() != null) {
            return List.of(bundle.rootDir().resolve(sourceFiles.commandFile()).toString());
        }
        return List.of(bundle.workflowPath().toString());
    }

    private static @Nullable StructuredWorkflowSummary structuredWorkflowSummary(TemplateBundle bundle) {
        if (!"structured".equals(bundle.workflow().effectiveRoutingMode())) {
            return null;
        }
        List<NodeRouteSummary> routes = new ArrayList<>();
        for (JsonNode node : bundle.workflow().nodes()) {
            JsonNode route = node.get("route");
            String routeMode = route == null ? "none" : textOr(route, "mode", "none");
            List<RouteCaseSummary> cases = new ArrayList<>();
            if (route != null && route.has("cases") && route.get("cases").isArray()) {
                for (JsonNode routeCase : route.get("cases")) {
                    cases.add(new RouteCaseSummary(
                            textOr(routeCase, "equals", ""),
                            text(routeCase, "to"),
                            text(routeCase, "continue_loop")));
                }
            }
            routes.add(new NodeRouteSummary(
                    textOr(node, "id", ""),
                    textOr(node, "kind", ""),
                    routeMode,
                    route == null ? null : text(route, "field"),
                    route == null ? null : text(route, "to"),
                    cases,
                    route == null || route.get("default") == null ? null : text(route.get("default"), "to")));
        }
        List<LoopSummary> loops = new ArrayList<>();
        JsonNode rawLoops = bundle.workflow().loops();
        if (rawLoops != null && rawLoops.isArray()) {
            for (JsonNode loop : rawLoops) {
                JsonNode budget = loop.get("budget");
                String budgetKind = budget == null ? "" : textOr(budget, "kind", "");
                loops.add(new LoopSummary(
                        textOr(loop, "id", ""),
                        textOr(loop, "controller_node", ""),
                        textOr(loop, "entry_node", ""),
                        budgetKind,
                        budget == null || !budget.has("max_iterations") ? null : budget.get("max_iterations").asLong(),
                        budget == null ? null : text(budget, "artifact"),
                        budget == null ? null : text(budget, "field"),
                        loop.get("on_exhaust") == null ? "" : textOr(loop.get("on_exhaust"), "to", "")));
            }
        }
        return new StructuredWorkflowSummary(routes, loops);
    }

    private static EmittedArtifacts writeAnalysisArtifacts(TemplateRunAnalysisResult result, Path outDir) {
        Filesystem.ensureDirectory(outDir);
        Path analysisJson = outDir.resolve("analysis.json");
        Path summaryMd = outDir.resolve("summary.md");
        Filesystem.writeUtf8(analysisJson, Json.toPrettyJson(result));
        Filesystem.writeUtf8(summaryMd, renderSummaryMarkdown(result));
        return new EmittedArtifacts(analysisJson.toString(), summaryMd.toString());
    }

    private static List<FileProposal> buildFileProposals(TemplateBundle bundle, TemplateRunAnalysisResult analysis) {
        List<FileProposal> proposals = new ArrayList<>();
        proposals.add(new FileProposal(
                bundle.rootDir().resolve("PROPOSED_UPDATES.md"),
                Path.of("PROPOSED_UPDATES.md"),
                readOptional(bundle.rootDir().resolve("PROPOSED_UPDATES.md")),
                renderProposedUpdatesMarkdown(bundle, analysis)));
        Map<Path, List<ImprovementSuggestion>> byTarget = new TreeMap<>();
        for (ImprovementSuggestion suggestion : analysis.suggestions()) {
            byTarget.computeIfAbsent(Path.of(suggestion.targetFile()), ignored -> new ArrayList<>()).add(suggestion);
        }
        for (Map.Entry<Path, List<ImprovementSuggestion>> entry : byTarget.entrySet()) {
            FileProposal proposal = buildTargetFileProposal(bundle.rootDir(), entry.getKey(), entry.getValue());
            if (proposal != null) {
                proposals.add(proposal);
            }
        }
        proposals.sort(Comparator.comparing(FileProposal::relativePath));
        List<FileProposal> deduped = new ArrayList<>();
        for (FileProposal proposal : proposals) {
            if (deduped.stream().noneMatch(existing -> existing.relativePath().equals(proposal.relativePath()))) {
                deduped.add(proposal);
            }
        }
        return List.copyOf(deduped);
    }

    private static @Nullable FileProposal buildTargetFileProposal(
            Path bundleRoot,
            Path targetPath,
            List<ImprovementSuggestion> suggestions
    ) {
        String original = Filesystem.readUtf8(targetPath);
        Path relative = relativePath(bundleRoot, targetPath);
        Path fileName = targetPath.getFileName();
        String proposed = switch (extension(targetPath)) {
            case "txt" -> proposePromptText(original, suggestions);
            case "sh" -> proposeShellScript(original, suggestions);
            case "json" -> fileName != null
                    && "workflow.json".equals(fileName.toString())
                    ? proposeWorkflowJson(original, suggestions)
                    : original;
            default -> original;
        };
        if (Objects.equals(original, proposed)) {
            return null;
        }
        return new FileProposal(targetPath, relative, original, proposed);
    }

    private static String proposePromptText(String original, List<ImprovementSuggestion> suggestions) {
        StringBuilder result = new StringBuilder(stripTrailingNewlines(original));
        for (ImprovementSuggestion suggestion : suggestions.stream().sorted(Comparator.comparing(ImprovementSuggestion::id)).toList()) {
            result.append("\n\n")
                    .append("[Run-analysis proposal: ").append(suggestion.id()).append("]\n")
                    .append("Priority: ").append(suggestion.priority()).append('\n')
                    .append("Summary: ").append(suggestion.summary()).append('\n')
                    .append("Rationale: ").append(suggestion.rationale()).append('\n')
                    .append("Patch hint: ").append(suggestion.patchHint()).append('\n')
                    .append("Runs: ").append(String.join(", ", suggestion.runSlugs())).append('\n');
        }
        return result.append('\n').toString();
    }

    private static String proposeShellScript(String original, List<ImprovementSuggestion> suggestions) {
        List<String> lines = new ArrayList<>(original.lines().toList());
        int insertionIndex = !lines.isEmpty() && lines.getFirst().startsWith("#!") ? 1 : 0;
        List<String> comments = new ArrayList<>();
        comments.add("# Proposed run-analysis updates:");
        for (ImprovementSuggestion suggestion : suggestions.stream().sorted(Comparator.comparing(ImprovementSuggestion::id)).toList()) {
            comments.add("# - [" + suggestion.priority() + "] " + suggestion.summary());
            comments.add("#   Rationale: " + suggestion.rationale());
            comments.add("#   Patch hint: " + suggestion.patchHint());
            comments.add("#   Runs: " + String.join(", ", suggestion.runSlugs()));
        }
        comments.add("");
        lines.addAll(insertionIndex, comments);
        return String.join("\n", lines) + "\n";
    }

    private static String proposeWorkflowJson(String original, List<ImprovementSuggestion> suggestions) {
        JsonNode parsed;
        try {
            parsed = Json.mapper().readTree(original);
        } catch (IOException error) {
            throw new ForgeException("failed to parse workflow.json while preparing a proposal patch", error);
        }
        if (!(parsed instanceof ObjectNode root)) {
            return original;
        }
        JsonNode nodes = root.get("nodes");
        if (nodes == null || !nodes.isArray()) {
            return original;
        }
        boolean changed = false;
        for (ImprovementSuggestion suggestion : suggestions.stream().sorted(Comparator.comparing(ImprovementSuggestion::id)).toList()) {
            if (suggestion.targetNode() == null) {
                continue;
            }
            for (JsonNode node : nodes) {
                if (!suggestion.targetNode().equals(text(node, "id")) || !(node instanceof ObjectNode object)) {
                    continue;
                }
                if ("human_instruction".equals(suggestion.category())) {
                    String note = suggestion.summary() + " " + suggestion.patchHint();
                    String current = textOr(object, "instructions", "");
                    object.put("instructions", current.isBlank()
                            ? "Run-analysis proposal: " + note
                            : current + "\n\nRun-analysis proposal: " + note);
                    changed = true;
                } else if ("retry_policy".equals(suggestion.category())) {
                    ObjectNode retry = object.withObject("/retry_policy");
                    long current = retry.path("max_attempts").asLong(1L);
                    retry.put("max_attempts", Math.min(UNSIGNED_INT_MAX, current + 1L));
                    changed = true;
                }
            }
        }
        return changed ? Json.toPrettyJson(root) : original;
    }

    private static ProposalArtifacts writeProposalArtifacts(
            TemplateBundle bundle,
            TemplateRunAnalysisResult analysis,
            List<FileProposal> proposals,
            Path outDir
    ) {
        Filesystem.ensureDirectory(outDir);
        EmittedArtifacts analysisArtifacts = writeAnalysisArtifacts(analysis, outDir);
        Path proposalPatch = outDir.resolve("proposal.patch");
        Path proposedFilesDir = outDir.resolve("proposed-files");
        Filesystem.ensureDirectory(proposedFilesDir);
        for (FileProposal proposal : proposals) {
            Filesystem.writeUtf8(proposedFilesDir.resolve(proposal.relativePath()), proposal.proposedText());
        }
        Filesystem.writeUtf8(proposalPatch, renderUnifiedPatch(bundle, proposals));
        return new ProposalArtifacts(
                analysisArtifacts.analysisJson(),
                analysisArtifacts.summaryMd(),
                proposalPatch.toString(),
                proposedFilesDir.toString());
    }

    private static String renderUnifiedPatch(TemplateBundle bundle, List<FileProposal> proposals) {
        StringBuilder patch = new StringBuilder();
        for (FileProposal proposal : proposals) {
            patch.append(renderFilePatch(bundle, proposal)).append('\n');
        }
        return patch.toString();
    }

    private static String renderFilePatch(TemplateBundle bundle, FileProposal proposal) {
        String displayPath = bundle.manifest().id() + "/" + proposal.relativePath();
        List<String> oldLines = patchLines(proposal.originalText());
        List<String> newLines = patchLines(proposal.proposedText());
        StringBuilder result = new StringBuilder();
        result.append("diff --git a/").append(displayPath).append(" b/").append(displayPath).append('\n');
        result.append(proposal.originalText() == null ? "--- /dev/null" : "--- a/" + displayPath).append('\n');
        result.append("+++ b/").append(displayPath).append('\n');
        result.append("@@ -")
                .append(oldLines.isEmpty() ? 0 : 1)
                .append(',')
                .append(oldLines.size())
                .append(" +")
                .append(newLines.isEmpty() ? 0 : 1)
                .append(',')
                .append(newLines.size())
                .append(" @@\n");
        for (String line : oldLines) {
            result.append('-').append(line).append('\n');
        }
        for (String line : newLines) {
            result.append('+').append(line).append('\n');
        }
        return result.toString();
    }

    private static List<String> patchLines(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return stripTrailingNewlines(text).lines().toList();
    }

    private static String renderSummaryMarkdown(TemplateRunAnalysisResult result) {
        List<String> lines = new ArrayList<>();
        lines.add("# Template Run Analysis: " + result.templateId());
        lines.add("");
        lines.add("- Source: `" + result.source() + "`");
        lines.add("- Workflow: `" + result.workflowId() + "`");
        lines.add("- Effective routing mode: `" + result.effectiveRoutingMode() + "` (" + result.routingModeSource() + ")");
        lines.add("- Runs directory: `" + result.runsDir() + "`");
        lines.add("- Analyzed runs: " + result.analyzedRunCount());
        lines.add("- Skipped runs: " + result.skippedRunCount());
        lines.add("");
        lines.add("## Summary");
        lines.add("");
        lines.add("- Completed runs: " + result.summary().completedRuns());
        lines.add("- Escalated runs: " + result.summary().escalatedRuns());
        lines.add("- Failed runs: " + result.summary().failedRuns());
        lines.add("- Pending runs: " + result.summary().pendingRuns());
        lines.add("- Dispatch failures: " + result.summary().totalDispatchFailures());
        lines.add("- Human rework signals: " + result.summary().totalHumanReworkSignals());
        lines.add("- Artifact findings: " + result.summary().totalInvalidArtifacts());
        lines.add("");
        if (result.structuredWorkflow() != null) {
            lines.add("## Structured Routes");
            lines.add("");
            for (NodeRouteSummary route : result.structuredWorkflow().nodeRoutes()) {
                lines.add("### `" + route.nodeId() + "`");
                lines.add("");
                lines.add("- Kind: `" + route.kind() + "`");
                lines.add("- Route mode: `" + route.routeMode() + "`");
                if (route.routeField() != null) {
                    lines.add("- Route field: `" + route.routeField() + "`");
                }
                if (route.target() != null) {
                    lines.add("- Target: `" + route.target() + "`");
                }
                for (RouteCaseSummary routeCase : route.cases()) {
                    String action = routeCase.target() != null
                            ? "to `" + routeCase.target() + "`"
                            : routeCase.continueLoop() == null
                            ? "unconfigured"
                            : "continue_loop `" + routeCase.continueLoop() + "`";
                    lines.add("- Case `" + routeCase.equals() + "` -> " + action);
                }
                if (route.defaultTarget() != null) {
                    lines.add("- Default: `" + route.defaultTarget() + "`");
                }
                lines.add("");
            }
            lines.add("## Loop Budgets");
            lines.add("");
            if (result.structuredWorkflow().loops().isEmpty()) {
                lines.add("- No declared loops.");
            } else {
                for (LoopSummary loop : result.structuredWorkflow().loops()) {
                    lines.add("### `" + loop.loopId() + "`");
                    lines.add("");
                    lines.add("- Controller node: `" + loop.controllerNode() + "`");
                    lines.add("- Entry node: `" + loop.entryNode() + "`");
                    lines.add("- Budget kind: `" + loop.budgetKind() + "`");
                    lines.add("- On exhaust: `" + loop.onExhaustTarget() + "`");
                    lines.add("");
                }
            }
        }
        lines.add("## Suggestions");
        lines.add("");
        if (result.suggestions().isEmpty()) {
            lines.add("- No suggestions were generated from the analyzed runs.");
        } else {
            for (ImprovementSuggestion suggestion : result.suggestions()) {
                lines.add("- [" + suggestion.priority() + "] " + suggestion.summary() + " -> `" + suggestion.targetFile() + "`");
                lines.add("  - Rationale: " + suggestion.rationale());
                lines.add("  - Patch hint: " + suggestion.patchHint());
                lines.add("  - Runs: " + String.join(", ", suggestion.runSlugs()));
            }
        }
        lines.add("");
        return String.join("\n", lines);
    }

    private static String renderProposedUpdatesMarkdown(TemplateBundle bundle, TemplateRunAnalysisResult analysis) {
        List<String> lines = new ArrayList<>();
        lines.add("# Proposed Updates: " + bundle.manifest().id());
        lines.add("");
        lines.add("- Source: `" + analysis.source() + "`");
        lines.add("- Workflow: `" + analysis.workflowId() + "`");
        lines.add("- Analyzed runs: " + analysis.analyzedRunCount());
        lines.add("- Suggestions: " + analysis.suggestions().size());
        lines.add("");
        for (ImprovementSuggestion suggestion : analysis.suggestions()) {
            lines.add("## " + suggestion.summary());
            lines.add("");
            lines.add("- Id: `" + suggestion.id() + "`");
            lines.add("- Category: `" + suggestion.category() + "`");
            lines.add("- Priority: `" + suggestion.priority() + "`");
            lines.add("- Target file: `" + suggestion.targetFile() + "`");
            if (suggestion.targetNode() != null) {
                lines.add("- Target node: `" + suggestion.targetNode() + "`");
            }
            lines.add("- Runs: " + String.join(", ", suggestion.runSlugs()));
            lines.add("");
            lines.add("Rationale:");
            lines.add(suggestion.rationale());
            lines.add("");
            lines.add("Patch hint:");
            lines.add(suggestion.patchHint());
            lines.add("");
        }
        return String.join("\n", lines) + "\n";
    }

    private static @Nullable String readOptional(Path path) {
        return Files.isRegularFile(path) ? Filesystem.readUtf8(path) : null;
    }

    private static Path relativePath(Path root, Path path) {
        try {
            return root.toRealPath().relativize(path.toRealPath());
        } catch (IOException ignored) {
            Path fileName = path.getFileName();
            return fileName == null ? Path.of("unknown") : fileName;
        }
    }

    private static String extension(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return "";
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }

    private static String stripTrailingNewlines(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        return text.substring(0, end);
    }

    private static String runStatusLabel(RunStatus status) {
        return switch (status) {
            case IDLE -> "idle";
            case RUNNING -> "running";
            case WAITING_FOR_AGENT -> "waiting_for_agent";
            case WAITING_FOR_HUMAN -> "waiting_for_human";
            case WAITING_FOR_SUBRUN -> "waiting_for_subrun";
            case COMPLETED -> "completed";
            case FAILED -> "failed";
            case ESCALATED -> "escalated";
        };
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requiredText(EventEnvelope event, String field) {
        String value = event.textField(field);
        if (value == null || value.isBlank()) {
            throw new ForgeException("event " + event.type() + " is missing " + field);
        }
        return value;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record TemplateRunAnalysisResult(
            String status,
            String source,
            @JsonProperty("template_id") String templateId,
            @JsonProperty("workflow_id") String workflowId,
            @JsonProperty("effective_routing_mode") String effectiveRoutingMode,
            @JsonProperty("routing_mode_source") String routingModeSource,
            List<String> warnings,
            @JsonProperty("runs_dir") String runsDir,
            @JsonProperty("analyzed_run_count") int analyzedRunCount,
            @JsonProperty("skipped_run_count") int skippedRunCount,
            @JsonProperty("analyzed_slugs") List<String> analyzedSlugs,
            AnalysisSummary summary,
            @JsonProperty("node_metrics") List<NodeMetric> nodeMetrics,
            @JsonProperty("artifact_findings") List<ArtifactFinding> artifactFindings,
            List<ImprovementSuggestion> suggestions,
            @JsonProperty("structured_workflow") @Nullable StructuredWorkflowSummary structuredWorkflow,
            @JsonProperty("emitted_artifacts") @Nullable EmittedArtifacts emittedArtifacts
    ) {
        public TemplateRunAnalysisResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            analyzedSlugs = analyzedSlugs == null ? List.of() : List.copyOf(analyzedSlugs);
            nodeMetrics = nodeMetrics == null ? List.of() : List.copyOf(nodeMetrics);
            artifactFindings = artifactFindings == null ? List.of() : List.copyOf(artifactFindings);
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        }

        private TemplateRunAnalysisResult withEmittedArtifacts(EmittedArtifacts emitted) {
            return new TemplateRunAnalysisResult(
                    status,
                    source,
                    templateId,
                    workflowId,
                    effectiveRoutingMode,
                    routingModeSource,
                    warnings,
                    runsDir,
                    analyzedRunCount,
                    skippedRunCount,
                    analyzedSlugs,
                    summary,
                    nodeMetrics,
                    artifactFindings,
                    suggestions,
                    structuredWorkflow,
                    emitted);
        }
    }

    public record AnalysisSummary(
            @JsonProperty("completed_runs") int completedRuns,
            @JsonProperty("escalated_runs") int escalatedRuns,
            @JsonProperty("failed_runs") int failedRuns,
            @JsonProperty("pending_runs") int pendingRuns,
            @JsonProperty("total_events") int totalEvents,
            @JsonProperty("total_dispatch_failures") int totalDispatchFailures,
            @JsonProperty("total_human_rework_signals") int totalHumanReworkSignals,
            @JsonProperty("total_invalid_artifacts") int totalInvalidArtifacts
    ) {
    }

    public record NodeMetric(
            @JsonProperty("node_id") String nodeId,
            String kind,
            @JsonProperty("target_files") List<String> targetFiles,
            @JsonProperty("configured_max_attempts") long configuredMaxAttempts,
            @JsonProperty("run_count") int runCount,
            @JsonProperty("total_attempts") int totalAttempts,
            @JsonProperty("extra_attempts") int extraAttempts,
            @JsonProperty("dispatch_failures") int dispatchFailures,
            int escalations,
            @JsonProperty("human_rework_signals") int humanReworkSignals,
            int loopbacks,
            @JsonProperty("run_slugs") List<String> runSlugs
    ) {
        public NodeMetric {
            targetFiles = targetFiles == null ? List.of() : List.copyOf(targetFiles);
            runSlugs = runSlugs == null ? List.of() : List.copyOf(runSlugs);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ArtifactFinding(
            @JsonProperty("artifact_name") String artifactName,
            @JsonProperty("producer_node") @Nullable String producerNode,
            @JsonProperty("issue_type") String issueType,
            @JsonProperty("media_type") @Nullable String mediaType,
            @JsonProperty("target_file") String targetFile,
            int count,
            @JsonProperty("run_slugs") List<String> runSlugs,
            String details
    ) {
        public ArtifactFinding {
            runSlugs = runSlugs == null ? List.of() : List.copyOf(runSlugs);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ImprovementSuggestion(
            String id,
            String category,
            String priority,
            @JsonProperty("target_file") String targetFile,
            @JsonProperty("target_node") @Nullable String targetNode,
            String summary,
            String rationale,
            @JsonProperty("patch_hint") String patchHint,
            @JsonProperty("run_slugs") List<String> runSlugs
    ) {
        public ImprovementSuggestion {
            runSlugs = runSlugs == null ? List.of() : List.copyOf(runSlugs);
        }
    }

    public record EmittedArtifacts(
            @JsonProperty("analysis_json") String analysisJson,
            @JsonProperty("summary_md") String summaryMd
    ) {
    }

    public record TemplateUpdateProposalResult(
            String status,
            String source,
            @JsonProperty("template_id") String templateId,
            @JsonProperty("workflow_id") String workflowId,
            @JsonProperty("runs_dir") String runsDir,
            @JsonProperty("analyzed_run_count") int analyzedRunCount,
            @JsonProperty("suggestion_count") int suggestionCount,
            @JsonProperty("proposed_file_count") int proposedFileCount,
            @JsonProperty("emitted_artifacts") ProposalArtifacts emittedArtifacts
    ) {
    }

    public record ProposalArtifacts(
            @JsonProperty("analysis_json") String analysisJson,
            @JsonProperty("summary_md") String summaryMd,
            @JsonProperty("proposal_patch") String proposalPatch,
            @JsonProperty("proposed_files_dir") String proposedFilesDir
    ) {
    }

    public record StructuredWorkflowSummary(
            @JsonProperty("node_routes") List<NodeRouteSummary> nodeRoutes,
            List<LoopSummary> loops
    ) {
        public StructuredWorkflowSummary {
            nodeRoutes = nodeRoutes == null ? List.of() : List.copyOf(nodeRoutes);
            loops = loops == null ? List.of() : List.copyOf(loops);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record NodeRouteSummary(
            @JsonProperty("node_id") String nodeId,
            String kind,
            @JsonProperty("route_mode") String routeMode,
            @JsonProperty("route_field") @Nullable String routeField,
            @Nullable String target,
            List<RouteCaseSummary> cases,
            @JsonProperty("default_target") @Nullable String defaultTarget
    ) {
        public NodeRouteSummary {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record RouteCaseSummary(
            String equals,
            @Nullable String target,
            @JsonProperty("continue_loop") @Nullable String continueLoop
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record LoopSummary(
            @JsonProperty("loop_id") String loopId,
            @JsonProperty("controller_node") String controllerNode,
            @JsonProperty("entry_node") String entryNode,
            @JsonProperty("budget_kind") String budgetKind,
            @JsonProperty("max_iterations") @Nullable Long maxIterations,
            @Nullable String artifact,
            @Nullable String field,
            @JsonProperty("on_exhaust_target") String onExhaustTarget
    ) {
    }

    private record CandidateRun(String slug, Path runDir) {
    }

    private record SourceWorkflowMap(Map<String, NodeSourceFiles> nodeSources) {
        private SourceWorkflowMap {
            nodeSources = Map.copyOf(new TreeMap<>(nodeSources));
        }
    }

    private record NodeSourceFiles(@Nullable String promptFile, @Nullable String commandFile) {
    }

    private record NodeInfo(String id, String kind, long configuredMaxAttempts, JsonNode source) {
    }

    private record RunMetrics(
            String slug,
            String status,
            int eventCount,
            @Nullable String escalationNode,
            Map<String, Integer> nodeAttempts,
            Map<String, Integer> nodeFailures,
            Map<String, Integer> nodeHumanReworkSignals,
            Map<String, Integer> nodeLoopbacks,
            List<ArtifactIssueOccurrence> artifactIssues
    ) {
        private RunMetrics {
            nodeAttempts = Map.copyOf(new TreeMap<>(nodeAttempts));
            nodeFailures = Map.copyOf(new TreeMap<>(nodeFailures));
            nodeHumanReworkSignals = Map.copyOf(new TreeMap<>(nodeHumanReworkSignals));
            nodeLoopbacks = Map.copyOf(new TreeMap<>(nodeLoopbacks));
            artifactIssues = List.copyOf(artifactIssues);
        }
    }

    private record ArtifactIssueOccurrence(
            String artifactName,
            @Nullable String producerNode,
            @Nullable String mediaType,
            String issueType,
            String detail
    ) {
    }

    private record ArtifactFindingKey(String artifactName, String issueType) implements Comparable<ArtifactFindingKey> {
        @Override
        public int compareTo(ArtifactFindingKey other) {
            int artifact = artifactName.compareTo(other.artifactName);
            return artifact != 0 ? artifact : issueType.compareTo(other.issueType);
        }
    }

    private static final class NodeAggregate {
        private final String kind;
        private final long configuredMaxAttempts;
        private final List<String> targetFiles;
        private final Set<String> runSlugs = new TreeSet<>();
        private int totalAttempts;
        private int dispatchFailures;
        private int escalations;
        private int humanReworkSignals;
        private int loopbacks;

        private NodeAggregate(String kind, long configuredMaxAttempts, List<String> targetFiles) {
            this.kind = kind;
            this.configuredMaxAttempts = configuredMaxAttempts;
            this.targetFiles = List.copyOf(targetFiles);
        }
    }

    private static final class ArtifactAggregate {
        private final @Nullable String producerNode;
        private final @Nullable String mediaType;
        private final String targetFile;
        private final Set<String> details = new TreeSet<>();
        private final Set<String> runSlugs = new TreeSet<>();
        private int count;

        private ArtifactAggregate(@Nullable String producerNode, @Nullable String mediaType, String targetFile) {
            this.producerNode = producerNode;
            this.mediaType = mediaType;
            this.targetFile = targetFile;
        }
    }

    private record FileProposal(
            Path sourcePath,
            Path relativePath,
            @Nullable String originalText,
            String proposedText
    ) {
    }
}
