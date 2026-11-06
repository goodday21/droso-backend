package org.example.ticket;

import java.net.URI;

import jakarta.validation.Valid;
import org.example.ticket.model.CreateTicketRequest;
import org.example.ticket.model.TicketResponse;
import org.example.security.AuthenticatedUser;
import org.example.user.Permission;
import org.example.web.CanonicalIdEditor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API of the tickets.
 *
 * <p>Invalid requests are answered by {@code ApiExceptionHandler} with {@code 400 VALIDATION_ERROR}
 * or {@code 400 MALFORMED_REQUEST}, a missing ticket with {@code 404 RESOURCE_NOT_FOUND}.
 *
 * <p>Every method needs a {@link Permission} of the user ({@code 403 FORBIDDEN} otherwise). The check
 * runs when the method is called, i.e. after the body has been read and validated: an invalid body
 * answers {@code 400} even to a user who lacks the permission.
 */
@RestController
@RequestMapping(TicketController.PATH)
public class TicketController {

    static final String PATH = "/api/v1/tickets";

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * Accepts the ids of the URLs in their canonical form only ({@code 26}, not {@code 0x1A} or
     * {@code 026}); anything else answers {@code 400 VALIDATION_ERROR}. See {@link CanonicalIdEditor}.
     */
    @InitBinder
    void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(Long.class, new CanonicalIdEditor());
    }

    /**
     * Creates a ticket reported by the current user and answers {@code 201 Created} with the stored
     * ticket. Needs {@link Permission#TICKET_CREATE}.
     *
     * <p>The {@code Location} is relative (RFC 9110 allows it): it stays correct behind a reverse
     * proxy, which an absolute URL built from the request would not without the forwarded headers.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('TICKET_CREATE')")
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody CreateTicketRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        TicketResponse created = ticketService.create(request, user.id());
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }

    /**
     * Answers {@code 200 OK} with the stored ticket, {@code 404 RESOURCE_NOT_FOUND} if there is none.
     * Needs {@link Permission#TICKET_READ}.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TICKET_READ')")
    public TicketResponse get(@PathVariable Long id) {
        return ticketService.get(id);
    }
}
