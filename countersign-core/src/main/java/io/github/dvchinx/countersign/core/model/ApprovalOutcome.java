package io.github.dvchinx.countersign.core.model;

/**
 * What happened to a call that needed human approval, as recorded in its receipt.
 *
 * <p>Only {@link #APPROVED} lets the tool run. Every other outcome refuses the call (fail closed).
 */
public enum ApprovalOutcome {
    /** A human approved the call before the deadline. */
    APPROVED,
    /** A human rejected the call. */
    REJECTED,
    /** Nobody answered before the deadline. */
    TIMED_OUT,
    /** No approver could be asked: none is configured, or the approval gateway failed. */
    UNAVAILABLE
}
