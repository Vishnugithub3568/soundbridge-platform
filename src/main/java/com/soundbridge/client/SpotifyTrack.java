package com.soundbridge.client;

public record SpotifyTrack(
    String name,
    String artist,
    String album,
    Long durationMs
) {
}
