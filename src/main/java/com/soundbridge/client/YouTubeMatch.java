package com.soundbridge.client;

public record YouTubeMatch(
    boolean matched,
    String targetTrackId,
    String targetTrackUrl,
    String targetTrackTitle,
    String targetThumbnailUrl,
    double confidenceScore,
    boolean partial,
    String failureReason
) {
}
