package com.soundbridge.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Client for YouTube Music API.
 * YouTube Music doesn't have a public API, so we use the unofficial API or fallback to web scraping.
 * For production, consider using Web Scraper or implementing YouTube Data API v3 with Music extensions.
 */
@Component
@SuppressWarnings("null")
public class YouTubeMusicClient {

    private static final Logger log = LoggerFactory.getLogger(YouTubeMusicClient.class);

    private final String apiBaseUrl;

    public YouTubeMusicClient(
        @Value("${youtube-music.api-base-url:https://music.youtube.com}") String apiBaseUrl
    ) {
        this.apiBaseUrl = Objects.requireNonNullElse(apiBaseUrl, "https://music.youtube.com");
    }

    /**
     * Parse YouTube Music playlist URL and extract playlist ID
     */
    public String extractPlaylistId(String playlistUrl) {
        if (playlistUrl == null || playlistUrl.isBlank()) {
            throw new IllegalArgumentException("Playlist URL cannot be empty");
        }

        String url = playlistUrl.trim();

        // Handle various YouTube Music URL formats
        // https://music.youtube.com/playlist?list=OLAK5...
        if (url.contains("list=")) {
            String[] parts = url.split("list=");
            if (parts.length > 1) {
                String id = parts[1].split("&")[0].split("#")[0];
                if (!id.isBlank()) {
                    return id;
                }
            }
        }

        // Handle share URLs
        // https://music.youtube.com/share/...
        if (url.contains("/share/")) {
            String id = url.substring(url.lastIndexOf("/share/") + 7).split("[&?#]")[0];
            if (!id.isBlank()) {
                return id;
            }
        }

        throw new IllegalArgumentException("Invalid YouTube Music playlist URL format");
    }

    public List<SpotifyTrack> fetchPlaylistTracks(String playlistUrl, String userAccessToken) {
        String playlistId = extractPlaylistId(playlistUrl);
        return fetchPlaylistTracksById(playlistId, userAccessToken);
    }

    /**
     * Fetch playlist metadata and tracks (stub - requires authentication)
     * In production, this would use YouTube Data API v3 with OAuth token
     */
    public List<SpotifyTrack> fetchPlaylistTracksById(String playlistId, String userAccessToken) {
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Playlist ID is required");
        }

        if (userAccessToken == null || userAccessToken.isBlank()) {
            throw new IllegalArgumentException("User access token is required for YouTube Music");
        }

        // TODO: Implement actual YouTube Music API call
        // This would require:
        // 1. YouTube Data API v3 credentials
        // 2. OAuth2 token exchange
        // 3. Fetching playlist items with music metadata

        log.warn("YouTube Music API fetch not yet implemented - returning empty playlist");
        return new ArrayList<>();
    }
}
