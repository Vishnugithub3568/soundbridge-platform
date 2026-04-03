package com.soundbridge.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateMigrationRequest {

    @NotBlank
    private String spotifyPlaylistUrl;

    private String spotifyAccessToken;

    private String googleAccessToken;

    public String getSpotifyPlaylistUrl() {
        return spotifyPlaylistUrl;
    }

    public void setSpotifyPlaylistUrl(String spotifyPlaylistUrl) {
        this.spotifyPlaylistUrl = spotifyPlaylistUrl;
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
