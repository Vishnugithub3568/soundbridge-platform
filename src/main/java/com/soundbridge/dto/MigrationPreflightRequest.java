package com.soundbridge.dto;

import jakarta.validation.constraints.NotBlank;

public class MigrationPreflightRequest {

    @NotBlank
    private String sourcePlaylistUrl;

    private String direction = "SPOTIFY_TO_YOUTUBE";

    private String spotifyAccessToken;

    private String googleAccessToken;

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
}