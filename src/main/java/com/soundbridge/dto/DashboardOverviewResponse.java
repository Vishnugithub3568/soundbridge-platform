package com.soundbridge.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DashboardOverviewResponse(
    UUID userId,
    String displayName,
    String email,
    int totalJobs,
    int completedJobs,
    int failedJobs,
    int runningJobs,
    int totalTracks,
    int connectedServices,
    Map<String, Long> telemetryCounters,
    List<String> quickActions,
    List<DashboardServiceStatusResponse> services
) {
}