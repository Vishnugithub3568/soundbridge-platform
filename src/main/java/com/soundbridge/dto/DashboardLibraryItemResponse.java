package com.soundbridge.dto;

import java.time.Instant;
import java.util.UUID;

public record DashboardLibraryItemResponse(
    UUID id,
    String title,
    String sourcePlatform,
    String targetPlatform,
    int tracks,
    String status,
    Instant updatedAt,
    String sourcePlaylistUrl,
    String targetPlaylistUrl
) {
}