package com.soundbridge.dto;

import com.soundbridge.model.JobStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MigrationReportResponse(
    UUID jobId,
    JobStatus status,
    int totalTracks,
    int matchedTracks,
    int failedTracks,
    double matchRate,
    List<MigrationTrackResponse> tracks,
    Map<String, Integer> issueCategoryCounts,
    String dominantIssueCategory,
    String dominantIssueAction
) {
}
