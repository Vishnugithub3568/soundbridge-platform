package com.soundbridge.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class SpotifyClient {

    private final RestTemplate restTemplate;
    private final String clientId;
    private final String clientSecret;
    private final String apiBaseUrl;
    private final String accountsBaseUrl;

    private volatile String cachedAccessToken;
    private volatile Instant cachedAccessTokenExpiry = Instant.EPOCH;

    public SpotifyClient(
        RestTemplateBuilder restTemplateBuilder,
        @Value("${spotify.client-id:}") String clientId,
        @Value("${spotify.client-secret:}") String clientSecret,
        @Value("${spotify.api-base-url:https://api.spotify.com/v1}") String apiBaseUrl,
        @Value("${spotify.accounts-base-url:https://accounts.spotify.com}") String accountsBaseUrl
    ) {
        this.restTemplate = restTemplateBuilder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.apiBaseUrl = apiBaseUrl;
        this.accountsBaseUrl = accountsBaseUrl;
    }

    public List<SpotifyTrack> fetchPlaylistTracks(String playlistUrl) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("Spotify credentials are not configured");
        }

        String playlistId = extractPlaylistId(playlistUrl);
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Invalid Spotify playlist URL");
        }

        List<SpotifyTrack> tracks = new ArrayList<>();
        String nextUrl = UriComponentsBuilder
            .fromHttpUrl(apiBaseUrl + "/playlists/{playlistId}/tracks")
            .queryParam("limit", 100)
            .buildAndExpand(playlistId)
            .toUriString();

        while (nextUrl != null && !nextUrl.isBlank()) {
            JsonNode page = getAuthorizedJson(nextUrl);
            JsonNode items = page.path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    JsonNode trackNode = item.path("track");
                    if (trackNode.isMissingNode() || trackNode.isNull()) {
                        continue;
                    }

                    String name = trackNode.path("name").asText("");
                    String album = trackNode.path("album").path("name").asText(null);
                    String artist = extractPrimaryArtist(trackNode.path("artists"));

                    if (!name.isBlank() && artist != null && !artist.isBlank()) {
                        tracks.add(new SpotifyTrack(name, artist, album));
                    }
                }
            }

            JsonNode nextNode = page.path("next");
            nextUrl = nextNode.isNull() ? null : nextNode.asText(null);
        }

        return tracks;
    }

    private JsonNode getAuthorizedJson(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getAccessToken());
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, JsonNode.class);
        return Objects.requireNonNull(response.getBody(), "Spotify API returned empty response body");
    }

    private synchronized String getAccessToken() {
        Instant now = Instant.now();
        if (cachedAccessToken != null && now.isBefore(cachedAccessTokenExpiry.minusSeconds(30))) {
            return cachedAccessToken;
        }

        HttpHeaders headers = new HttpHeaders();
        String basicToken = Base64.getEncoder()
            .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + basicToken);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
        String tokenUrl = accountsBaseUrl + "/api/token";

        ResponseEntity<JsonNode> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, request, JsonNode.class);
        JsonNode body = Objects.requireNonNull(response.getBody(), "Spotify token response is empty");

        String accessToken = body.path("access_token").asText("");
        long expiresIn = body.path("expires_in").asLong(3600L);
        if (accessToken.isBlank()) {
            throw new IllegalStateException("Spotify token response did not contain access_token");
        }

        cachedAccessToken = accessToken;
        cachedAccessTokenExpiry = Instant.now().plusSeconds(expiresIn);
        return cachedAccessToken;
    }

    private String extractPlaylistId(String playlistUrl) {
        if (playlistUrl == null || playlistUrl.isBlank()) {
            return null;
        }

        String normalized = playlistUrl.trim();
        int playlistIndex = normalized.indexOf("/playlist/");
        if (playlistIndex < 0) {
            return null;
        }

        String after = normalized.substring(playlistIndex + "/playlist/".length());
        int queryIndex = after.indexOf('?');
        if (queryIndex >= 0) {
            after = after.substring(0, queryIndex);
        }

        int slashIndex = after.indexOf('/');
        if (slashIndex >= 0) {
            after = after.substring(0, slashIndex);
        }

        return after;
    }

    private String extractPrimaryArtist(JsonNode artistsNode) {
        if (artistsNode == null || !artistsNode.isArray() || artistsNode.isEmpty()) {
            return null;
        }
        return artistsNode.get(0).path("name").asText(null);
    }
}
