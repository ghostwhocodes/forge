package dev.llaith.forge.workflow.state;

public enum RunStatus {
    IDLE,
    RUNNING,
    WAITING_FOR_AGENT,
    WAITING_FOR_HUMAN,
    WAITING_FOR_SUBRUN,
    COMPLETED,
    FAILED,
    ESCALATED
}
