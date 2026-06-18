package dev.llaith.forge.storage;

import dev.llaith.forge.runtime.run.RunSlug;

import java.nio.file.Path;

public final class RunPaths {
    private RunPaths() {
    }

    public static Path runDir(Path runsDir, RunSlug slug) {
        return runsDir.resolve(slug.asString());
    }

    public static Path stagingDir(Path runsDir, RunSlug slug) {
        return runsDir.resolve(".staging").resolve(slug.asString());
    }

    public static Path stagingLockPath(Path runsDir, RunSlug slug) {
        return runsDir.resolve(".staging").resolve(slug.asString() + ".lock");
    }

    public static Path runStateVersionPath(Path runDir) {
        return runDir.resolve("run-state-version");
    }

    public static Path specPath(Path runDir) {
        return runDir.resolve("spec.json");
    }

    public static Path workflowIrPath(Path runDir) {
        return runDir.resolve("workflow-ir.json");
    }

    public static Path workflowInterfacePath(Path runDir) {
        return runDir.resolve("workflow-interface.json");
    }

    public static Path eventsPath(Path runDir) {
        return runDir.resolve("events.ndjson");
    }

    public static Path artifactsDir(Path runDir) {
        return runDir.resolve("artifacts");
    }

    public static Path dispatchInputDir(Path runDir) {
        return runDir.resolve("dispatch").resolve("input");
    }

    public static Path dispatchOutputDir(Path runDir) {
        return runDir.resolve("dispatch").resolve("output");
    }

    public static Path projectionBacklogDir(Path runDir) {
        return runDir.resolve(".projection-backlog");
    }

    public static Path subrunsDir(Path runDir) {
        return runDir.resolve("subruns");
    }

    public static Path subrunFrozenWorkflowsDir(Path runDir) {
        return subrunsDir(runDir).resolve("frozen-workflows");
    }

    public static Path subrunFrozenWorkflowSourceDir(Path runDir, String snapshot) {
        return subrunFrozenWorkflowsDir(runDir).resolve(snapshot).resolve("source");
    }

    public static Path subrunChildrenDir(Path runDir) {
        return subrunsDir(runDir).resolve("children");
    }

    public static Path childRunDir(Path runDir, String childSlug) {
        return subrunChildrenDir(runDir).resolve(childSlug);
    }

    public static Path lockDir(Path runDir) {
        return runDir.resolve(".locks");
    }

    public static Path autoLockPath(Path runDir) {
        return runDir.resolve("auto.lock");
    }

    public static Path mutationLockPath(Path runDir) {
        return lockDir(runDir).resolve("mutation.lock");
    }

    public static Path dispatchExecutionLeasePath(Path runDir, String dispatchId) {
        return lockDir(runDir).resolve("dispatch-execution").resolve(dispatchId + ".lock");
    }
}
