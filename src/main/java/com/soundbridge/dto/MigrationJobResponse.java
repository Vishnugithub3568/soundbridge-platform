package com.soundbridge.dto;

import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MigrationJobResponse {
    UUID id;
    String sourcePlaylistUrl;
    String targetPlatform;
    JobStatus status;
    int totalTracks;
    int matchedTracks;
    int failedTracks;
    Instant createdAt;
    Instant updatedAt;

    public static MigrationJobResponse from(MigrationJob job) {
        return MigrationJobResponse.builder()
            .id(job.getId())
            .sourcePlaylistUrl(job.getSourcePlaylistUrl())
            .targetPlatform(job.getTargetPlatform())
            .status(job.getStatus())
            .totalTracks(job.getTotalTracks())
            .matchedTracks(job.getMatchedTracks())
            .failedTracks(job.getFailedTracks())
            .createdAt(job.getCreatedAt())
            .updatedAt(job.getUpdatedAt())
            .build();
    }
}
