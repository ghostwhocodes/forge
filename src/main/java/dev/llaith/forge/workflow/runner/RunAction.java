package dev.llaith.forge.workflow.runner;

import org.jspecify.annotations.Nullable;

public record RunAction(
        String type,
        @Nullable PendingDispatch dispatch,
        @Nullable PendingHumanReview humanReview,
        @Nullable PendingSubrun subrun,
        @Nullable String message,
        @Nullable String target,
        @Nullable String reason
) {
    public static RunAction dispatch(PendingDispatch dispatch) {
        return new RunAction("dispatch", dispatch, null, null, null, null, null);
    }

    public static RunAction humanReview(PendingHumanReview humanReview) {
        return new RunAction("human_review", null, humanReview, null, null, null, null);
    }

    public static RunAction subrun(PendingSubrun subrun) {
        return new RunAction("subrun", null, null, subrun, null, null, null);
    }

    public static RunAction complete(String message) {
        return new RunAction("complete", null, null, null, message, "__complete__", null);
    }

    public static RunAction escalate(String reason) {
        return new RunAction("escalate", null, null, null, null, "__escalate__", reason);
    }

    public static RunAction noop(String message) {
        return new RunAction("noop", null, null, null, message, null, null);
    }
}
