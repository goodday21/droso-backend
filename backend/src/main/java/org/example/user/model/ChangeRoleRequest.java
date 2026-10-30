package org.example.user.model;

import jakarta.validation.constraints.NotNull;
import org.example.user.Role;

/**
 * Request body of {@code PUT /api/v1/users/{id}/role}.
 *
 * @param role required; by name ({@code "MANAGER"}), an unknown name or an index answers {@code 400}
 */
public record ChangeRoleRequest(@NotNull Role role) {
}
