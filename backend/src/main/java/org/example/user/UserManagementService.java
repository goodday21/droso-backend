package org.example.user;

import java.time.Clock;

import org.example.user.model.UserPageResponse;
import org.example.user.model.UserResponse;
import org.example.web.error.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The administration of the users: the list, the role, the deletion. A deleted user is treated as
 * absent ({@code 404}) by every operation.
 *
 * <p>There is always at least one active administrator: demoting or deleting the last one throws
 * {@link LastAdminException}. A count alone would not hold under concurrency (two administrators
 * demoting each other would both see two), so every change first locks the rows of the active
 * administrators, then the row of its user. The order is the same for every change, so two of them
 * never wait for each other in a cycle; the lock of the user row also keeps a deletion and a role
 * change of the same user from overwriting each other (Hibernate writes every column).
 */
@Service
public class UserManagementService {

    private final UserRepository repository;
    private final Clock clock;

    public UserManagementService(UserRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** One page of the active users, in the order of {@link UserSort#toSort}. */
    @Transactional(readOnly = true)
    public UserPageResponse list(int page, int size, UserSort sort, Sort.Direction direction) {
        return UserPageResponse.of(
                repository.findByDeletedAtIsNull(PageRequest.of(page, size, sort.toSort(direction))));
    }

    /**
     * Gives the user another role; the same role changes nothing.
     *
     * @throws ResourceNotFoundException if there is no active user with this id
     * @throws LastAdminException        if the user is the last active administrator and the role
     *                                   is not {@link Role#ADMIN}
     */
    @Transactional
    public UserResponse changeRole(long id, Role role) {
        long otherAdmins = otherActiveAdmins(id);
        User user = lockActive(id);
        if (user.getRole() == Role.ADMIN && role != Role.ADMIN && otherAdmins == 0) {
            throw new LastAdminException();
        }
        user.changeRole(role);
        return UserResponse.of(user);
    }

    /**
     * Soft-deletes the user and revokes the tokens ({@link User#delete}).
     *
     * @throws ResourceNotFoundException if there is no active user with this id
     * @throws LastAdminException        if the user is the last active administrator
     */
    @Transactional
    public void delete(long id) {
        long otherAdmins = otherActiveAdmins(id);
        User user = lockActive(id);
        if (user.getRole() == Role.ADMIN && otherAdmins == 0) {
            throw new LastAdminException();
        }
        user.delete(clock.instant());
    }

    /** Locks the active administrators and counts those other than the user. */
    private long otherActiveAdmins(long id) {
        return repository.lockActiveAdmins().stream().filter(admin -> admin.getId() != id).count();
    }

    private User lockActive(long id) {
        return repository.lockById(id)
                .filter(User::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }
}
