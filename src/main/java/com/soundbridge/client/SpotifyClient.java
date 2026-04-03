package com.soundbridge.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
@SuppressWarnings("null")
public class SpotifyClient {

    private static final Logger log = LoggerFactory.getLogger(SpotifyClient.class);
    private static final long MAX_BACKOFF_MS = 5000L;
    private static final Pattern INITIAL_STATE_PATTERN = Pattern.compile(
        "<script id=\\\"initialState\\\" type=\\\"text/plain\\\">(.*?)</script>",
        Pattern.DOTALL
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;
    private final String apiBaseUrl;
    private final String accountsBaseUrl;
    private final boolean safeFallbackEnabled;
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
        @Value("${spotify.safe-fallback.enabled:false}") boolean safeFallbackEnabled,
        @Value("${api.retry.max-attempts:3}") int retryMaxAttempts,
        @Value("${api.retry.initial-backoff-ms:250}") long retryInitialBackoffMs
    ) {
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = new ObjectMapper();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.apiBaseUrl = apiBaseUrl;
        this.accountsBaseUrl = accountsBaseUrl;
        this.safeFallbackEnabled = safeFallbackEnabled;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryInitialBackoffMs = Math.max(50L, retryInitialBackoffMs);
    }

    public List<SpotifyTrack> fetchPlaylistTracks(String playlistUrl) {
        return fetchPlaylistTracks(playlistUrl, null);
    }

    public List<SpotifyTrack> fetchPlaylistTracks(String playlistUrl, String spotifyUserAccessToken) {
        String normalizedUserToken = spotifyUserAccessToken == null ? "" : spotifyUserAccessToken.trim();
        boolean hasUserToken = !normalizedUserToken.isEmpty();

        String playlistId = extractPlaylistId(playlistUrl);
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Invalid Spotify playlist URL");
        }

        if (!hasUserToken && (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank())) {
            List<SpotifyTrack> publicTracks = fetchPublicPlaylistTracks(playlistId);
            if (!publicTracks.isEmpty()) {
                log.warn(
                    "Spotify credentials are not configured; extracted {} tracks from public page for playlistId={}",
                    publicTracks.size(),
                    playlistId
                );
                return publicTracks;
            }

            log.warn(
                "Spotify credentials are not configured and public page extraction failed for playlistId={}; using fallback demo tracks",
                playlistId
            );
            return fallbackDemoTracks();
        }

        List<SpotifyTrack> tracks = new ArrayList<>();
        String nextUrl = UriComponentsBuilder
            .fromUriString(apiBaseUrl + "/playlists/{playlistId}/tracks")
            .queryParam("limit", 100)
            .buildAndExpand(playlistId)
            .toUriString();

        try {
            while (nextUrl != null && !nextUrl.isBlank()) {
                JsonNode page = getAuthorizedJson(nextUrl, normalizedUserToken);
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
                        long durationMs = trackNode.path("duration_ms").asLong(0L);

                        if (!name.isBlank() && artist != null && !artist.isBlank()) {
                            tracks.add(new SpotifyTrack(name, artist, album, durationMs > 0 ? durationMs : null));
                        }
                    }
                }

                JsonNode nextNode = page.path("next");
                nextUrl = nextNode.isNull() ? null : nextNode.asText(null);
            }
        } catch (RuntimeException ex) {
            if (hasUserToken && isUnauthorized(ex)) {
                log.warn(
                    "Spotify user token rejected for playlistId={} (reason={}); retrying without user token",
                    playlistId,
                    summarizeError(ex)
                );
                return fetchPlaylistTracks(playlistUrl, null);
            }

            if (!hasUserToken) {
                List<SpotifyTrack> publicTracks = fetchPublicPlaylistTracks(playlistId);
                if (!publicTracks.isEmpty()) {
                    log.warn(
                        "Spotify API unavailable for playlistId={} (reason={}); extracted {} tracks from public page",
                        playlistId,
                        summarizeError(ex),
                        publicTracks.size()
                    );
                    return publicTracks;
                }

                log.warn(
                    "Spotify API and public page extraction unavailable for playlistId={} (reason={}); using fallback demo tracks",
                    playlistId,
                    summarizeError(ex)
                );
                return fallbackDemoTracks();
            }

            if (!(safeFallbackEnabled && isSafeFallbackCandidate(ex))) {
                throw ex;
            }

            log.warn(
                "Spotify playlist fetch unavailable for playlistId={} (reason={}); using configured fallback demo tracks",
                playlistId,
                summarizeError(ex)
            );
            return fallbackDemoTracks();
        }

        return tracks;
    }

    private List<SpotifyTrack> fetchPublicPlaylistTracks(String playlistId) {
        String playlistPageUrl = "https://open.spotify.com/playlist/" + playlistId;
        try {
            String html = restTemplate.getForObject(playlistPageUrl, String.class);
            if (html == null || html.isBlank()) {
                return List.of();
            }

            Matcher matcher = INITIAL_STATE_PATTERN.matcher(html);
            if (!matcher.find()) {
                return List.of();
            }

            String encodedState = matcher.group(1).trim();
            if (encodedState.isBlank()) {
                return List.of();
            }

            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(encodedState);
            } catch (IllegalArgumentException ex) {
                decoded = Base64.getUrlDecoder().decode(encodedState);
            }

            JsonNode root = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
            Set<String> seen = new LinkedHashSet<>();
            List<SpotifyTrack> extracted = new ArrayList<>();
            collectTracksFromNode(root, extracted, seen);
            return extracted;
        } catch (RuntimeException ex) {
            log.warn("Public Spotify page fallback failed for playlistId={} reason={}", playlistId, summarizeError(ex));
            return List.of();
        } catch (Exception ex) {
            log.warn("Public Spotify page fallback parse failed for playlistId={} reason={}", playlistId, ex.getMessage());
            return List.of();
        }
    }

    private void collectTracksFromNode(JsonNode node, List<SpotifyTrack> out, Set<String> seen) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            String uri = node.path("uri").asText("");
            String name = node.path("name").asText("");
            String artist = extractArtistFromTrackNode(node);
            Long durationMs = extractDurationFromTrackNode(node);

            if (uri.startsWith("spotify:track:") && !name.isBlank() && !artist.isBlank()) {
                String dedupeKey = uri + "|" + name + "|" + artist;
                if (seen.add(dedupeKey)) {
                    out.add(new SpotifyTrack(name, artist, null, durationMs));
                }
            }

            node.fields().forEachRemaining(entry -> collectTracksFromNode(entry.getValue(), out, seen));
            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                collectTracksFromNode(child, out, seen);
            }
        }
    }

    private String extractArtistFromTrackNode(JsonNode trackNode) {
        JsonNode artistItems = trackNode.path("artists").path("items");
        if (artistItems.isArray() && !artistItems.isEmpty()) {
            JsonNode first = artistItems.get(0);
            String profileName = first.path("profile").path("name").asText("");
            if (!profileName.isBlank()) {
                return profileName;
            }
            String name = first.path("name").asText("");
            if (!name.isBlank()) {
                return name;
            }
        }

        JsonNode artistsArray = trackNode.path("artists");
        if (artistsArray.isArray() && !artistsArray.isEmpty()) {
            String name = artistsArray.get(0).path("name").asText("");
            if (!name.isBlank()) {
                return name;
            }
        }

        return "";
    }

    private Long extractDurationFromTrackNode(JsonNode trackNode) {
        long totalMs = trackNode.path("duration").path("totalMilliseconds").asLong(0L);
        if (totalMs > 0L) {
            return totalMs;
        }

        long durationMs = trackNode.path("duration_ms").asLong(0L);
        return durationMs > 0L ? durationMs : null;
    }

    private boolean isSafeFallbackCandidate(RuntimeException ex) {
        if (!(ex instanceof HttpStatusCodeException statusCodeException)) {
            return false;
        }

        int status = statusCodeException.getStatusCode().value();
        return status == 403 || status == 429;
    }

    private boolean isUnauthorized(RuntimeException ex) {
        if (!(ex instanceof HttpStatusCodeException statusCodeException)) {
            return false;
        }
        int status = statusCodeException.getStatusCode().value();
        return status == 401 || status == 403;
    }

    private List<SpotifyTrack> fallbackDemoTracks() {
        return List.of(
            new SpotifyTrack("Blinding Lights", "The Weeknd", "After Hours", 200040L),
            new SpotifyTrack("Levitating", "Dua Lipa", "Future Nostalgia", 203064L),
            new SpotifyTrack("Believer", "Imagine Dragons", "Evolve", 204346L),
            new SpotifyTrack("Shape of You", "Ed Sheeran", "Divide", 233712L),
            new SpotifyTrack("Heat Waves", "Glass Animals", "Dreamland", 238805L),
            new SpotifyTrack("As It Was", "Harry Styles", "Harry's House", 167303L),
            new SpotifyTrack("Numb", "Linkin Park", "Meteora", 185586L)
        );
    }

    private JsonNode getAuthorizedJson(String url, String spotifyUserAccessToken) {
        return executeWithRetry("spotify-playlist-tracks", () -> {
            HttpHeaders headers = new HttpHeaders();
            String userToken = spotifyUserAccessToken == null ? "" : spotifyUserAccessToken.trim();
            headers.setBearerAuth(userToken.isEmpty() ? getAccessToken() : userToken);
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
