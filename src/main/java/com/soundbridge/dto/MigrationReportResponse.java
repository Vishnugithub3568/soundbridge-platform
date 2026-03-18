package com.soundbridge.dto;

import com.soundbridge.model.JobStatus;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MigrationReportResponse {
    UUID jobId;
    JobStatus status;
    int totalTracks;
    int matchedTracks;
    int failedTracks;
    double matchRate;
    List<MigrationTrackResponse> tracks;
}
