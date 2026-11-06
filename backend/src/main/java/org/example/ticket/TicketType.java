package org.example.ticket;

/**
 * Kind of a {@link Ticket}: a missing capability or a fault of an existing one. Stored by its name
 * in {@code tickets.type}; a new value needs a migration replacing {@code tickets_type_check}.
 */
public enum TicketType {
    FEATURE,
    BUG
}
