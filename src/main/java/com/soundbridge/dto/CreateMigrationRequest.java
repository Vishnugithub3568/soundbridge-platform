package com.soundbridge.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to start a bidirectional migration between Spotify and YouTube Music.
 * Direction is determined by the sourcePlaylistUrl and targetService fields.
 */
public class CreateMigrationRequest {

    @NotBlank
    private String sourcePlaylistUrl;

    private String direction = "SPOTIFY_TO_YOUTUBE"; // SPOTIFY_TO_YOUTUBE or YOUTUBE_TO_SPOTIFY

    private String spotifyAccessToken;

    private String googleAccessToken;

    // Legacy field support - maps to sourcePlaylistUrl
    public String getSpotifyPlaylistUrl() {
        return sourcePlaylistUrl;
    }

    public void setSpotifyPlaylistUrl(String spotifyPlaylistUrl) {
        this.sourcePlaylistUrl = spotifyPlaylistUrl;
    }

    public String getSourcePlaylistUrl() {
        return sourcePlaylistUrl;
    }

    public void setSourcePlaylistUrl(String sourcePlaylistUrl) {
        this.sourcePlaylistUrl = sourcePlaylistUrl;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getSpotifyAccessToken() {
        return spotifyAccessToken;
    }

    public void setSpotifyAccessToken(String spotifyAccessToken) {
        this.spotifyAccessToken = spotifyAccessToken;
    }

    public String getGoogleAccessToken() {
        return googleAccessToken;
    }

    public void setGoogleAccessToken(String googleAccessToken) {
        this.googleAccessToken = googleAccessToken;
    }

    public boolean isSpotifyToYouTube() {
        return "SPOTIFY_TO_YOUTUBE".equalsIgnoreCase(direction);
    }

    public boolean isYouTubeToSpotify() {
        return "YOUTUBE_TO_SPOTIFY".equalsIgnoreCase(direction);
    }
}
