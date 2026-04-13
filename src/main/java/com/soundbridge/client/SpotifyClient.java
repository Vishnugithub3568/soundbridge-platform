package com.soundbridge.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final String DEFAULT_API_BASE_URL = "https://api.spotify.com/v1";
    private static final String DEFAULT_ACCOUNTS_BASE_URL = "https://accounts.spotify.com";
    private static final Pattern INITIAL_STATE_PATTERN = Pattern.compile(
        "<script id=\\\"initialState\\\" type=\\\"text/plain\\\">(.*?)</script>",
        Pattern.DOTALL
    );
    private static final Pattern BRACKETED_SEGMENTS_PATTERN = Pattern.compile("\\([^)]*\\)|\\[[^]]*]|\\{[^}]*}");
    private static final Pattern FEATURING_PATTERN = Pattern.compile("\\b(feat|ft|featuring|with)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE_TERMS_PATTERN = Pattern.compile(
        "\\b(official|video|audio|lyrics?|lyric|version|remaster(ed)?|mono|stereo|topic|hq|hd|from)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOPIC_SUFFIX_PATTERN = Pattern.compile("\\s*[-–—]\\s*topic$", Pattern.CASE_INSENSITIVE);

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
        this.apiBaseUrl = normalizeApiBaseUrl(apiBaseUrl);
        this.accountsBaseUrl = normalizeAccountsBaseUrl(accountsBaseUrl);
        this.safeFallbackEnabled = safeFallbackEnabled;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryInitialBackoffMs = Math.max(50L, retryInitialBackoffMs);
    }

    private String normalizeApiBaseUrl(String configured) {
        String candidate = Objects.requireNonNullElse(configured, "").trim();
        if (candidate.isBlank()) {
            return DEFAULT_API_BASE_URL;
        }

        try {
            URI uri = URI.create(candidate);
            String scheme = Objects.requireNonNullElse(uri.getScheme(), "").toLowerCase();
            String host = Objects.requireNonNullElse(uri.getHost(), "").toLowerCase();
            if (!"https".equals(scheme) || !"api.spotify.com".equals(host)) {
                log.warn(
                    "Invalid SPOTIFY_API_BASE_URL='{}'; falling back to default {}",
                    candidate,
                    DEFAULT_API_BASE_URL
                );
                return DEFAULT_API_BASE_URL;
            }

            String path = Objects.requireNonNullElse(uri.getPath(), "").trim();
            if (path.isBlank() || "/".equals(path)) {
                return DEFAULT_API_BASE_URL;
            }

            String normalized = candidate.endsWith("/") ? candidate.substring(0, candidate.length() - 1) : candidate;
            if (!path.startsWith("/v1")) {
                log.warn(
                    "SPOTIFY_API_BASE_URL path '{}' does not start with /v1; falling back to default {}",
                    path,
                    DEFAULT_API_BASE_URL
                );
                return DEFAULT_API_BASE_URL;
            }

            return normalized;
        } catch (RuntimeException ex) {
            log.warn(
                "Could not parse SPOTIFY_API_BASE_URL='{}'; falling back to default {}",
                candidate,
                DEFAULT_API_BASE_URL
            );
            return DEFAULT_API_BASE_URL;
        }
    }

    private String normalizeAccountsBaseUrl(String configured) {
        String candidate = Objects.requireNonNullElse(configured, "").trim();
        if (candidate.isBlank()) {
            return DEFAULT_ACCOUNTS_BASE_URL;
        }

        try {
            URI uri = URI.create(candidate);
            String scheme = Objects.requireNonNullElse(uri.getScheme(), "").toLowerCase();
            String host = Objects.requireNonNullElse(uri.getHost(), "").toLowerCase();
            if (!"https".equals(scheme) || !"accounts.spotify.com".equals(host)) {
                log.warn(
                    "Invalid SPOTIFY_ACCOUNTS_BASE_URL='{}'; falling back to default {}",
                    candidate,
                    DEFAULT_ACCOUNTS_BASE_URL
                );
                return DEFAULT_ACCOUNTS_BASE_URL;
            }

            return candidate.endsWith("/") ? candidate.substring(0, candidate.length() - 1) : candidate;
        } catch (RuntimeException ex) {
            log.warn(
                "Could not parse SPOTIFY_ACCOUNTS_BASE_URL='{}'; falling back to default {}",
                candidate,
                DEFAULT_ACCOUNTS_BASE_URL
            );
            return DEFAULT_ACCOUNTS_BASE_URL;
        }
    }

    public List<SpotifyTrack> fetchPlaylistTracks(String playlistUrl) {
        return fetchPlaylistTracks(playlistUrl, null);
    }

    public List<SpotifyTrack> fetchPlaylistTracks(String playlistUrl, String spotifyUserAccessToken) {
        String normalizedUserToken = spotifyUserAccessToken == null ? "" : spotifyUserAccessToken.trim();
        boolean hasUserToken = !normalizedUserToken.isEmpty();

        String normalizedPlaylistUrl = Objects.requireNonNullElse(playlistUrl, "").trim();
        if (normalizedPlaylistUrl.contains("/album/")) {
            throw new IllegalArgumentException(
                "Spotify album URL detected. Please use a playlist URL (https://open.spotify.com/playlist/{id})."
            );
        }

        String playlistId = extractPlaylistId(playlistUrl);
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException(
                "Invalid Spotify playlist URL. Please use format: https://open.spotify.com/playlist/{id}."
            );
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

            throw new IllegalStateException(
                "Spotify source playlist is not publicly readable and backend credentials are unavailable."
                    + " Connect Spotify and retry for private/collaborative playlists."
            );
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
                throw new IllegalStateException(
                    "Spotify access token was rejected while reading source playlist."
                        + " Reconnect Spotify and retry migration.",
                    ex
                );
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

                throw new IllegalStateException(
                    "Spotify source playlist could not be read from API or public page."
                        + " If the playlist is private, reconnect Spotify and retry.",
                    ex
                );
            }

            if (safeFallbackEnabled && isSafeFallbackCandidate(ex)) {
                log.warn(
                    "Spotify source fetch hit fallback candidate for playlistId={} (reason={}); failing safe to prevent incorrect track migration",
                    playlistId,
                    summarizeError(ex)
                );
            }

            throw ex;
        }

        return tracks;
    }

    public List<SpotifySearchCandidate> searchTrackCandidates(String trackName, String artistName, String spotifyUserAccessToken) {
        String normalizedTrackName = Objects.requireNonNullElse(trackName, "").trim();
        String normalizedArtistName = Objects.requireNonNullElse(artistName, "").trim();
        if (normalizedTrackName.isBlank() || normalizedArtistName.isBlank()) {
            return List.of();
        }

        List<String> queries = buildSearchQueries(normalizedTrackName, normalizedArtistName);
        if (queries.isEmpty()) {
            return List.of();
        }

        List<SpotifySearchCandidate> candidates = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        String normalizedUserToken = normalizeBearerToken(spotifyUserAccessToken);
        boolean hasUserToken = !normalizedUserToken.isBlank();

        for (String query : queries) {
            UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(apiBaseUrl + "/search")
                .queryParam("q", query)
                .queryParam("type", "track")
                .queryParam("limit", 15);

            if (hasUserToken) {
                // Request only tracks playable for the authenticated Spotify user.
                builder.queryParam("market", "from_token");
            }

            String url = builder.build().toUriString();

            JsonNode body = getAuthorizedJson(url, spotifyUserAccessToken);
            JsonNode items = body.path("tracks").path("items");
            if (!items.isArray() || items.isEmpty()) {
                continue;
            }

            for (JsonNode item : items) {
                String id = item.path("id").asText("");
                String name = item.path("name").asText("");
                String uri = item.path("uri").asText("");
                String externalUrl = item.path("external_urls").path("spotify").asText("");
                String album = item.path("album").path("name").asText(null);
                String artist = extractPrimaryArtist(item.path("artists"));
                String thumbnailUrl = extractAlbumThumbnail(item.path("album").path("images"));

                if (id.isBlank() || name.isBlank() || uri.isBlank() || !seenIds.add(id)) {
                    continue;
                }

                candidates.add(new SpotifySearchCandidate(id, uri, name, artist, album, externalUrl, thumbnailUrl));

                if (candidates.size() >= 25) {
                    return candidates;
                }
            }
        }

        return candidates;
    }

    private List<String> buildSearchQueries(String trackName, String artistName) {
        String cleanedTrack = normalizeForSearch(trackName);
        String cleanedArtist = normalizeArtistForSearch(artistName);
        String primaryArtist = extractPrimaryArtistToken(cleanedArtist);

        Set<String> queries = new LinkedHashSet<>();
        queries.add("track:" + trackName + " artist:" + artistName);

        if (!cleanedTrack.isBlank() && !cleanedArtist.isBlank()) {
            queries.add("track:" + cleanedTrack + " artist:" + cleanedArtist);
        }

        if (!cleanedTrack.isBlank() && !primaryArtist.isBlank()) {
            queries.add("track:" + cleanedTrack + " artist:" + primaryArtist);
            queries.add(cleanedTrack + " " + primaryArtist);
        }

        if (!cleanedTrack.isBlank()) {
            queries.add(cleanedTrack);
        }

        return queries.stream().filter(query -> !query.isBlank()).toList();
    }

    private String normalizeArtistForSearch(String artistName) {
        String cleaned = normalizeForSearch(artistName);
        if (cleaned.isBlank()) {
            return "";
        }

        cleaned = TOPIC_SUFFIX_PATTERN.matcher(cleaned).replaceAll("").trim();
        return cleaned;
    }

    private String extractPrimaryArtistToken(String artistName) {
        String normalized = Objects.requireNonNullElse(artistName, "").trim();
        if (normalized.isBlank()) {
            return "";
        }

        String[] splits = normalized.split(",|&|/|\\bx\\b|\\band\\b");
        String primary = splits.length == 0 ? normalized : splits[0].trim();
        return primary.isBlank() ? normalized : primary;
    }

    private String normalizeForSearch(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank()) {
            return "";
        }

        normalized = BRACKETED_SEGMENTS_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = FEATURING_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = NOISE_TERMS_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = normalized
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();

        return normalized;
    }

    public String createPlaylist(String spotifyUserAccessToken, String title, String description) {
        String token = normalizeBearerToken(spotifyUserAccessToken);
        if (token.isBlank()) {
            throw new IllegalArgumentException("Spotify access token is required to create Spotify playlist");
        }

        String playlistTitle = Objects.requireNonNullElse(title, "SoundBridge Migration").trim();
        if (playlistTitle.isBlank()) {
            playlistTitle = "SoundBridge Migration";
        }

        String url = UriComponentsBuilder
            .fromUriString("https://api.spotify.com/v1/me/playlists")
            .build()
            .toUriString();

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", playlistTitle);
        body.put("description", Objects.requireNonNullElse(description, "Migrated by SoundBridge"));
        body.put("public", Boolean.FALSE);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        JsonNode payload = exchangeForJson(
            "spotify-create-playlist",
            url,
            HttpMethod.POST,
            new HttpEntity<>(body, headers)
        );
        String playlistId = payload == null ? "" : payload.path("id").asText("");
        if (playlistId.isBlank()) {
            throw new IllegalStateException("Spotify playlist creation response did not include id");
        }

        return playlistId;
    }

    public void addTrackToPlaylist(String spotifyUserAccessToken, String playlistId, String trackUri) {
        String token = normalizeBearerToken(spotifyUserAccessToken);
        if (token.isBlank()) {
            throw new IllegalArgumentException("Spotify access token is required to add items to Spotify playlist");
        }
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Spotify playlist id is required");
        }
        if (trackUri == null || trackUri.isBlank()) {
            throw new IllegalArgumentException("Spotify track uri is required");
        }

        String url = UriComponentsBuilder
            .fromUriString(apiBaseUrl + "/playlists/{playlistId}/tracks")
            .buildAndExpand(playlistId)
            .toUriString();

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("uris", List.of(trackUri));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        exchangeForJson(
            "spotify-add-playlist-track",
            url,
            HttpMethod.POST,
            new HttpEntity<>(body, headers)
        );
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

    private String extractAlbumThumbnail(JsonNode imagesNode) {
        if (!imagesNode.isArray() || imagesNode.isEmpty()) {
            return null;
        }

        JsonNode first = imagesNode.get(0);
        String url = first.path("url").asText("");
        return url.isBlank() ? null : url;
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

    private JsonNode getAuthorizedJson(String url, String spotifyUserAccessToken) {
        HttpHeaders headers = new HttpHeaders();
        String userToken = normalizeBearerToken(spotifyUserAccessToken);
        headers.setBearerAuth(userToken.isEmpty() ? getAccessToken() : userToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
        return exchangeForJson("spotify-playlist-tracks", url, HttpMethod.GET, requestEntity);
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

        JsonNode body = exchangeForJson("spotify-token", tokenUrl, HttpMethod.POST, request);

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

    private JsonNode exchangeForJson(String operationName, String url, HttpMethod method, HttpEntity<?> requestEntity) {
        return executeWithRetry(operationName, () -> {
            var response = restTemplate.exchange(url, method, requestEntity, String.class);
            String payload = Objects.requireNonNullElse(response.getBody(), "").trim();
            MediaType contentType = response.getHeaders().getContentType();

            if (payload.isBlank()) {
                throw new IllegalStateException("Spotify API returned empty response body");
            }

            try {
                return objectMapper.readTree(payload);
            } catch (Exception ex) {
                if (looksLikeHtml(payload)) {
                    String type = contentType == null ? "unknown" : contentType.toString();
                    throw new IllegalStateException(
                        "Spotify API returned HTML content (" + type + ") for " + method + " " + url + "."
                            + " Verify SPOTIFY_API_BASE_URL and reconnect Spotify."
                    );
                }

                if (!isJsonPayload(contentType, payload)) {
                    String type = contentType == null ? "unknown" : contentType.toString();
                    throw new IllegalStateException(
                        "Spotify API returned non-JSON content (" + type + ") for " + method + " " + url + "."
                            + " Verify SPOTIFY_API_BASE_URL and reconnect Spotify."
                    );
                }

                throw new IllegalStateException("Spotify API returned malformed JSON payload", ex);
            }
        });
    }

    private String normalizeBearerToken(String token) {
        String normalized = Objects.requireNonNullElse(token, "").trim();
        if (normalized.isEmpty()) {
            return "";
        }

        if (normalized.regionMatches(true, 0, "Bearer ", 0, 7)) {
            normalized = normalized.substring(7).trim();
        }

        if (normalized.length() >= 2
            && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'")))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        return normalized;
    }

    private boolean looksLikeHtml(String payload) {
        String normalized = payload.toLowerCase();
        return normalized.startsWith("<!doctype html")
            || normalized.startsWith("<html")
            || normalized.contains("<head")
            || normalized.contains("<body");
    }

    private boolean isJsonPayload(MediaType contentType, String payload) {
        if (contentType != null) {
            String subtype = Objects.requireNonNullElse(contentType.getSubtype(), "").toLowerCase();
            if (subtype.contains("json")) {
                return true;
            }
        }

        return payload.startsWith("{") || payload.startsWith("[");
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

    public record SpotifySearchCandidate(
        String id,
        String uri,
        String name,
        String artist,
        String album,
        String externalUrl,
        String thumbnailUrl
    ) {}

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
