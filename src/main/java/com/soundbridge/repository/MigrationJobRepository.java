package com.soundbridge.repository;

import com.soundbridge.model.MigrationJob;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationJobRepository extends JpaRepository<MigrationJob, UUID> {
}
