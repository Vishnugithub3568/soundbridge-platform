package com.soundbridge.client;

public record YouTubeCandidate(
    String videoId,
    String title,
    String channelTitle,
    String thumbnailUrl
) {
}
