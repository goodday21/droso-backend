package org.example.ticket;

import org.example.ticket.model.CreateTicketRequest;
import org.example.ticket.model.TicketResponse;
import org.example.web.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases of the tickets. The entity never leaves this class: the web layer gets a
 * {@link TicketResponse}.
 */
@Service
public class TicketService {

    private final TicketRepository repository;

    public TicketService(TicketRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates a ticket with the status {@link TicketStatus#NEW}. The request is expected to be
     * validated already; {@link Ticket} still enforces the title rule on its own.
     *
     * @param authorId the id of the user who reports it, from the access token (never from the body)
     */
    @Transactional
    public TicketResponse create(CreateTicketRequest request, long authorId) {
        Ticket ticket = new Ticket(request.title(), request.description(), request.stepsToReproduce(),
                request.expectedResult(), request.actualResult(), request.priority(), request.type(), authorId);
        // saveAndFlush: the INSERT runs here, so the id and the timestamps of the response are the
        // ones of the stored row, and a failure of the database surfaces inside this method.
        return TicketResponse.of(repository.saveAndFlush(ticket));
    }

    /**
     * Returns the stored active ticket with the given id; a deleted one is absent, as is a deleted
     * user.
     *
     * <p>Read-only: Hibernate does not flush the session, and the connection is put into read-only
     * mode, so PostgreSQL itself refuses a write by mistake.
     *
     * @throws ResourceNotFoundException if there is no active ticket with this id
     */
    @Transactional(readOnly = true)
    public TicketResponse get(long id) {
        return repository.findActiveById(id)
                .map(TicketResponse::of)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", id));
    }
}
