package com.soundbridge.dto;

import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MigrationJobResponse(
    UUID id,
    String sourcePlaylistUrl,
    String targetPlatform,
    JobStatus status,
    int totalTracks,
    int matchedTracks,
    int failedTracks,
    Instant createdAt,
    Instant updatedAt
) {
    public static MigrationJobResponse from(MigrationJob job) {
        Objects.requireNonNull(job, "job must not be null");
        return new MigrationJobResponse(
            job.getId(),
            job.getSourcePlaylistUrl(),
            job.getTargetPlatform(),
            job.getStatus(),
            job.getTotalTracks(),
            job.getMatchedTracks(),
            job.getFailedTracks(),
            job.getCreatedAt(),
            job.getUpdatedAt()
        );
    }
}
