package com.soundbridge.dto;

import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MigrationJobResponse(
    UUID id,
    UUID userId,
    String sourcePlaylistUrl,
    String targetPlatform,
    String targetPlaylistId,
    String targetPlaylistUrl,
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
            job.getUserId(),
            job.getSourcePlaylistUrl(),
            job.getTargetPlatform(),
            job.getTargetPlaylistId(),
            job.getTargetPlaylistUrl(),
            job.getStatus(),
            job.getTotalTracks(),
            job.getMatchedTracks(),
            job.getFailedTracks(),
            job.getCreatedAt(),
            job.getUpdatedAt()
        );
    }
}
