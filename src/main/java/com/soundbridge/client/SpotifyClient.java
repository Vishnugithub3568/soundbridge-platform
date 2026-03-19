package com.soundbridge.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class SpotifyClient {

    private static final Logger log = LoggerFactory.getLogger(SpotifyClient.class);
    private static final long MAX_BACKOFF_MS = 5000L;

    private final RestTemplate restTemplate;
    private final String clientId;
    private final String clientSecret;
    private final String apiBaseUrl;
    private final String accountsBaseUrl;
    private final int retryMaxAttempts;
    private final long retryInitialBackoffMs;

    private volatile String cachedAccessToken;
    private volatile Instant cachedAccessTokenExpiry = Instant.EPOCH;

    public SpotifyClient(
        RestTemplateBuilder restTemplateBuilder,
        @Value("${spotify.client-id:}") String clientId,
        @Value("${spotify.client-secret:}") String clientSecret,
        @Value("${spotify.api-base-url:https://api.spotify.com/v1}") String apiBaseUrl,
        @Value("${spotify.accounts-base-url:https://accounts.spotify.com}") String accountsBaseUrl,
        @Value("${api.retry.max-attempts:3}") int retryMaxAttempts,
        @Value("${api.retry.initial-backoff-ms:250}") long retryInitialBackoffMs
    ) {
        this.restTemplate = restTemplateBuilder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.apiBaseUrl = apiBaseUrl;
        this.accountsBaseUrl = accountsBaseUrl;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryInitialBackoffMs = Math.max(50L, retryInitialBackoffMs);
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
        return executeWithRetry("spotify-playlist-tracks", () -> {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(getAccessToken());
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

            ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, JsonNode.class);
            return Objects.requireNonNull(response.getBody(), "Spotify API returned empty response body");
        });
    }

    private synchronized String getAccessToken() {
        Instant now = Instant.now();
        if (cachedAccessToken != null && now.isBefore(cachedAccessTokenExpiry.minusSeconds(30))) {
            return cachedAccessToken;
        }

        return refreshAccessToken();
    }

    private synchronized String refreshAccessToken() {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("Spotify credentials are not configured");
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

        ResponseEntity<JsonNode> response = executeWithRetry(
            "spotify-token",
            () -> restTemplate.exchange(tokenUrl, HttpMethod.POST, request, JsonNode.class)
        );
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

    private synchronized void clearCachedAccessToken() {
        cachedAccessToken = null;
        cachedAccessTokenExpiry = Instant.EPOCH;
    }

    private <T> T executeWithRetry(String operationName, Supplier<T> apiCall) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            try {
                return apiCall.get();
            } catch (RuntimeException ex) {
                last = ex;

                if (ex instanceof HttpStatusCodeException statusCodeException
                    && statusCodeException.getStatusCode().value() == 401) {
                    clearCachedAccessToken();
                }

                boolean retryable = isRetryable(ex);
                if (!retryable || attempt == retryMaxAttempts) {
                    throw ex;
                }

                long delayMs = calculateBackoffMillis(attempt);
                log.warn(
                    "Spotify API retry | operation={} attempt={}/{} delayMs={} reason={}",
                    operationName,
                    attempt,
                    retryMaxAttempts,
                    delayMs,
                    summarizeError(ex)
                );

                sleep(delayMs, operationName);
            }
        }

        throw Objects.requireNonNullElseGet(last, () -> new IllegalStateException("Unexpected retry failure"));
    }

    private boolean isRetryable(RuntimeException ex) {
        if (ex instanceof ResourceAccessException) {
            return true;
        }

        if (ex instanceof HttpStatusCodeException statusCodeException) {
            int status = statusCodeException.getStatusCode().value();
            return status == 408 || status == 429 || statusCodeException.getStatusCode().is5xxServerError();
        }

        return ex instanceof RestClientException;
    }

    private long calculateBackoffMillis(int attempt) {
        long multiplier = 1L << Math.max(0, attempt - 1);
        long computed = retryInitialBackoffMs * multiplier;
        if (computed < 0L) {
            return MAX_BACKOFF_MS;
        }
        return Math.min(MAX_BACKOFF_MS, computed);
    }

    private void sleep(long delayMs, String operationName) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry interrupted for " + operationName, interruptedException);
        }
    }

    private String summarizeError(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
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
