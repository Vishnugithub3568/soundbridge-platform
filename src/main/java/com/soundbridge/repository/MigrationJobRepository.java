package com.soundbridge.repository;

import com.soundbridge.model.MigrationJob;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationJobRepository extends JpaRepository<MigrationJob, UUID> {
    List<MigrationJob> findByUserIdOrderByUpdatedAtDesc(UUID userId, Pageable pageable);
}
