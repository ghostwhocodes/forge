package dev.llaith.forge.storage;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.spec.WorkflowSpecs;
import dev.llaith.forge.util.Filesystem;
import dev.llaith.forge.util.Json;

import java.nio.file.Path;

public final class RunFiles {
    public static final int RUN_STATE_VERSION = 1;

    private RunFiles() {
    }

    public static WorkflowSpec readSpec(Path runDir) {
        return WorkflowSpecs.load(RunPaths.specPath(runDir));
    }

    public static void writeSpec(Path runDir, WorkflowSpec spec) {
        WorkflowSpecs.write(RunPaths.specPath(runDir), spec);
    }

    public static JsonNode readWorkflowIr(Path runDir) {
        return Json.read(RunPaths.workflowIrPath(runDir), JsonNode.class);
    }

    public static void writeWorkflowIr(Path runDir, JsonNode ir) {
        Json.writePretty(RunPaths.workflowIrPath(runDir), ir);
    }

    public static JsonNode readWorkflowInterface(Path runDir) {
        return Json.read(RunPaths.workflowInterfacePath(runDir), JsonNode.class);
    }

    public static void writeWorkflowInterface(Path runDir, JsonNode workflowInterface) {
        Json.writePretty(RunPaths.workflowInterfacePath(runDir), workflowInterface);
    }

    public static void writeRunStateVersion(Path runDir) {
        Filesystem.writeUtf8(RunPaths.runStateVersionPath(runDir), RUN_STATE_VERSION + "\n");
    }

    public static void ensureSupportedRunState(Path runDir) {
        String raw = Filesystem.readUtf8(RunPaths.runStateVersionPath(runDir)).trim();
        int version;
        try {
            version = Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw new dev.llaith.forge.ForgeException(
                    "error: run at " + runDir + " has an unreadable prerelease state marker "
                            + RunPaths.runStateVersionPath(runDir)
                            + "; delete the run directory and reinitialize it",
                    error);
        }
        if (version != RUN_STATE_VERSION) {
            throw new dev.llaith.forge.ForgeException(
                    "error: run at " + runDir + " uses unsupported prerelease state version "
                            + version + "; this build expects " + RUN_STATE_VERSION
                            + "; delete the run directory and reinitialize it");
        }
    }
}
