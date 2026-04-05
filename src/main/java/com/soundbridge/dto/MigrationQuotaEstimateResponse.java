package com.soundbridge.dto;

public record MigrationQuotaEstimateResponse(
    String direction,
    int totalTracks,
    int estimatedQuotaUnits,
    boolean warning,
    String warningMessage
) {
}
