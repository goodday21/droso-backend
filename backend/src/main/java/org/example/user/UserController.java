package org.example.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.example.user.model.ChangeRoleRequest;
import org.example.user.model.UserPageResponse;
import org.example.user.model.UserResponse;
import org.example.web.CanonicalIdEditor;
import org.example.web.LowerCaseEnumEditor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administration of the users. Every method needs {@link Permission#USER_MANAGE}
 * ({@code 403 FORBIDDEN} otherwise), i.e. the role {@code ADMIN}.
 *
 * <p>Invalid requests are answered by {@code ApiExceptionHandler} with {@code 400 VALIDATION_ERROR}
 * or {@code 400 MALFORMED_REQUEST}, a missing or deleted user with {@code 404 RESOURCE_NOT_FOUND},
 * a change that would leave no active administrator with {@code 409 LAST_ADMIN}. As everywhere,
 * the permission is checked after the body has been validated.
 */
@RestController
@RequestMapping(UserController.PATH)
public class UserController {

    static final String PATH = "/api/v1/users";

    /** The largest page; a larger {@code size} answers {@code 400} rather than being cut silently. */
    static final int MAX_PAGE_SIZE = 100;

    private final UserManagementService service;

    public UserController(UserManagementService service) {
        this.service = service;
    }

    /**
     * The ids of the URLs in their canonical form only ({@link CanonicalIdEditor}); the field and the
     * direction of the order in lower case only ({@link LowerCaseEnumEditor}).
     */
    @InitBinder
    void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(Long.class, new CanonicalIdEditor());
        binder.registerCustomEditor(UserSort.class, new LowerCaseEnumEditor<>(UserSort.class));
        binder.registerCustomEditor(Sort.Direction.class, new LowerCaseEnumEditor<>(Sort.Direction.class));
    }

    /**
     * Answers {@code 200 OK} with a page of the active users. {@code page} counts from 0,
     * {@code size} is 1..100. {@code sort} is {@code id} (default), {@code email} or {@code role},
     * {@code direction} is {@code asc} or {@code desc} (default, also when only {@code sort} is
     * given): without parameters the newest users come first. A value outside answers
     * {@code 400 VALIDATION_ERROR}.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public UserPageResponse list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(defaultValue = "id") UserSort sort,
            @RequestParam(defaultValue = "desc") Sort.Direction direction) {
        return service.list(page, size, sort, direction);
    }

    /**
     * Gives the user another role and answers {@code 200 OK} with the user. The tokens of the user
     * stay valid; the new permissions apply from the next request.
     */
    @PutMapping(path = "/{id}/role", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public UserResponse changeRole(@PathVariable Long id, @Valid @RequestBody ChangeRoleRequest request) {
        return service.changeRole(id, request.role());
    }

    /**
     * Soft-deletes the user and answers {@code 204 No Content}. Every token of the user is revoked at
     * once; the email may be registered again.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
