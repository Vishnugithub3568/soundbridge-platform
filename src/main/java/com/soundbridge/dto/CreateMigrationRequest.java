package com.soundbridge.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateMigrationRequest {

    @NotBlank
    private String spotifyPlaylistUrl;

    public String getSpotifyPlaylistUrl() {
        return spotifyPlaylistUrl;
    }

    public void setSpotifyPlaylistUrl(String spotifyPlaylistUrl) {
        this.spotifyPlaylistUrl = spotifyPlaylistUrl;
    }
}
