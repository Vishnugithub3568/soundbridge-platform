package com.soundbridge.dto;

import java.util.List;

public record MigrationPreflightResponse(
    boolean validUrl,
    boolean readyToStart,
    String direction,
    String sourcePlatform,
    String targetPlatform,
    String playlistId,
    List<String> blockers,
    List<String> recommendations
) {
}