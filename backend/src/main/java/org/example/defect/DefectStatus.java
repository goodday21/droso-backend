package org.example.defect;

/**
 * Status of a {@link Defect}. Stored by its name in {@code defects.status}; a new value needs a
 * migration replacing {@code defects_status_check}, and a name must fit the {@code VARCHAR(16)}
 * column.
 *
 * <p>A new defect is always {@link #NEW}. The transitions between the statuses are not defined yet:
 * the order of the constants follows the usual life of a defect but is not a rule.
 */
public enum DefectStatus {
    NEW,
    IN_DEVELOPMENT,
    IN_REVIEW,
    READY_FOR_QA,
    IN_QA,
    WAITING_FOR_FIX,
    READY_TO_RELEASE,
    RELEASED,
    DECLINED
}
