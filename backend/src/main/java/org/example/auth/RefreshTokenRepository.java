package org.example.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * The token with this hash, its row locked until the end of the transaction
     * ({@code SELECT ... FOR UPDATE}). Two refreshes with one token are thereby serialized: the
     * second reads the row again once the first has committed, and finds it revoked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<RefreshToken> lockByTokenHash(byte[] tokenHash);

    /**
     * Revokes the live tokens of the family.
     *
     * @return how many were live
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(UUID familyId, Instant now);

    /**
     * Revokes the live tokens of the user.
     *
     * @return how many were live
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllOfUser(Long userId, Instant now);
}
