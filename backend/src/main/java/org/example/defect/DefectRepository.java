package org.example.defect;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DefectRepository extends JpaRepository<Defect, Long> {

    /**
     * The defect with the given id unless it is soft-deleted. Every read of the API goes through
     * this rather than {@link #findById}, which also finds the deleted ones.
     */
    @Query("SELECT d FROM Defect d WHERE d.id = :id AND d.deletedAt IS NULL")
    Optional<Defect> findActiveById(long id);
}
