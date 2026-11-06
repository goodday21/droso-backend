package org.example.ticket;

/**
 * Status of a {@link Ticket}. Stored by its name in {@code tickets.status}; a new value needs a
 * migration replacing {@code tickets_status_check}, and a name must fit the {@code VARCHAR(16)}
 * column.
 *
 * <p>A new ticket is always {@link #NEW}. The transitions between the statuses are not defined yet:
 * the order of the constants follows the usual life of a ticket but is not a rule.
 */
public enum TicketStatus {
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
