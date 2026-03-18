package com.soundbridge.repository;

import com.soundbridge.model.MigrationTrack;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationTrackRepository extends JpaRepository<MigrationTrack, Long> {
    List<MigrationTrack> findByJobIdOrderByIdAsc(UUID jobId);
}
