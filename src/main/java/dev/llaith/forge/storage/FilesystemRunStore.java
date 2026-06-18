package dev.llaith.forge.storage;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.util.Filesystem;
import dev.llaith.forge.workflow.event.EventEnvelope;
import dev.llaith.forge.workflow.reducer.WorkflowReducer;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

public final class FilesystemRunStore {
    public StoredRunContext loadRunContext(Path runDir) {
        RunFiles.ensureSupportedRunState(runDir);
        WorkflowSpec spec = RunFiles.readSpec(runDir);
        JsonNode ir = RunFiles.readWorkflowIr(runDir);
        JsonNode workflowInterface = RunFiles.readWorkflowInterface(runDir);
        Path eventsPath = RunPaths.eventsPath(runDir);
        return new StoredRunContext(
                runDir,
                spec,
                ir,
                workflowInterface,
                eventsPath,
                WorkflowReducer.deriveState(EventLog.readEvents(eventsPath)));
    }

    public void appendEvent(Path runDir, EventEnvelope event) {
        try (AdvisoryLock ignored = AdvisoryLock.acquire(RunPaths.mutationLockPath(runDir), "forge run mutation")) {
            EventLog.appendEvent(RunPaths.eventsPath(runDir), event);
        }
    }

    public void appendEvents(Path runDir, List<EventEnvelope> events) {
        try (AdvisoryLock ignored = AdvisoryLock.acquire(RunPaths.mutationLockPath(runDir), "forge run mutation")) {
            for (EventEnvelope event : events) {
                EventLog.appendEvent(RunPaths.eventsPath(runDir), event);
            }
        }
    }

    public boolean appendEventAfter(Path runDir, long expectedLastSeq, EventEnvelope event) {
        try (AdvisoryLock ignored = AdvisoryLock.acquire(RunPaths.mutationLockPath(runDir), "forge run mutation")) {
            long actualLastSeq = EventLog.lastEventSeq(RunPaths.eventsPath(runDir)).orElse(0L);
            if (actualLastSeq != expectedLastSeq) {
                return false;
            }
            long expectedEventSeq = expectedLastSeq + 1L;
            if (event.seq() != expectedEventSeq) {
                throw new IllegalArgumentException("event seq must be " + expectedEventSeq + ", got " + event.seq());
            }
            EventLog.appendEvent(RunPaths.eventsPath(runDir), event);
            return true;
        }
    }

    public <T> T mutateRun(Path runDir, Function<StoredRunContext, T> mutation) {
        try (AdvisoryLock ignored = AdvisoryLock.acquire(RunPaths.mutationLockPath(runDir), "forge run mutation")) {
            return mutation.apply(loadRunContext(runDir));
        }
    }

    public List<ProjectionBacklogEntry> projectionBacklog(Path runDir) {
        StoredRunContext context = loadRunContext(runDir);
        long lastEventSeq = context.state().lastEventSeq() == null ? 0 : context.state().lastEventSeq();
        return ProjectionBacklog.inspect(runDir, lastEventSeq);
    }

    public List<ProjectionBacklogEntry> recoverProjectionBacklog(Path runDir) {
        try (AdvisoryLock ignored = AdvisoryLock.acquire(RunPaths.mutationLockPath(runDir), "recover projection backlog")) {
            StoredRunContext context = loadRunContext(runDir);
            long lastEventSeq = context.state().lastEventSeq() == null ? 0 : context.state().lastEventSeq();
            return ProjectionBacklog.recover(runDir, lastEventSeq);
        }
    }

    public void writeFrozenRunFiles(
            Path runDir,
            WorkflowSpec spec,
            JsonNode workflowIr,
            JsonNode workflowInterface) {
        Filesystem.ensureDirectory(runDir);
        RunFiles.writeSpec(runDir, spec);
        RunFiles.writeWorkflowIr(runDir, workflowIr);
        RunFiles.writeWorkflowInterface(runDir, workflowInterface);
        RunFiles.writeRunStateVersion(runDir);
    }
}
