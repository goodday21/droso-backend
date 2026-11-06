package org.example.ticket;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /**
     * The ticket with the given id unless it is soft-deleted. Every read of the API goes through
     * this rather than {@link #findById}, which also finds the deleted ones.
     */
    @Query("SELECT t FROM Ticket t WHERE t.id = :id AND t.deletedAt IS NULL")
    Optional<Ticket> findActiveById(long id);
}
