package org.example.user.model;

import java.util.List;

import org.example.user.User;
import org.springframework.data.domain.Page;

/**
 * A page of users, as answered by {@code GET /api/v1/users}.
 *
 * <p>A record of its own rather than Spring Data's {@code Page}, whose JSON form is not a stable
 * contract (Spring Data itself warns against serializing it).
 *
 * @param items         the users of the page, in the requested order
 * @param page          the number of the page, from 0
 * @param size          the requested size of a page
 * @param totalElements the number of active users
 * @param totalPages    the number of pages of that size
 */
public record UserPageResponse(List<UserResponse> items, int page, int size, long totalElements, int totalPages) {

    public static UserPageResponse of(Page<User> users) {
        return new UserPageResponse(users.map(UserResponse::of).getContent(), users.getNumber(), users.getSize(),
                users.getTotalElements(), users.getTotalPages());
    }
}
