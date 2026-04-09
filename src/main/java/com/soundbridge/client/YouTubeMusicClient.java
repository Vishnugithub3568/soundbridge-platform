package com.soundbridge.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Locale;
import java.util.function.Supplier;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Client for YouTube Music API.
 * YouTube Music doesn't have a public API, so we use the unofficial API or fallback to web scraping.
 * For production, consider using Web Scraper or implementing YouTube Data API v3 with Music extensions.
 */
@Component
@SuppressWarnings("null")
public class YouTubeMusicClient {

    private static final Logger log = LoggerFactory.getLogger(YouTubeMusicClient.class);
    private static final int PAGE_SIZE = 50;
    private static final String DEFAULT_API_BASE_URL = "https://www.googleapis.com/youtube/v3";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;

    public YouTubeMusicClient(
        RestTemplateBuilder restTemplateBuilder,
        @Value("${youtube-music.api-base-url:${youtube.api-base-url:https://www.googleapis.com/youtube/v3}}") String apiBaseUrl
    ) {
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = new ObjectMapper();
        this.apiBaseUrl = normalizeApiBaseUrl(apiBaseUrl);
    }

    private String normalizeApiBaseUrl(String configured) {
        String candidate = Objects.requireNonNullElse(configured, "").trim();
        if (candidate.isBlank()) {
            return DEFAULT_API_BASE_URL;
        }

        try {
            URI uri = URI.create(candidate);
            String scheme = Objects.requireNonNullElse(uri.getScheme(), "").toLowerCase(Locale.ROOT);
            String host = Objects.requireNonNullElse(uri.getHost(), "").toLowerCase(Locale.ROOT);
            String path = Objects.requireNonNullElse(uri.getPath(), "").trim();

            if (!"https".equals(scheme)) {
                log.warn(
                    "Invalid YOUTUBE_MUSIC_API_BASE_URL='{}' (scheme must be https); falling back to {}",
                    candidate,
                    DEFAULT_API_BASE_URL
                );
                return DEFAULT_API_BASE_URL;
            }

            if (!"www.googleapis.com".equals(host) || !path.startsWith("/youtube/v3")) {
                log.warn(
                    "Invalid YOUTUBE_MUSIC_API_BASE_URL='{}'; expected https://www.googleapis.com/youtube/v3; falling back to {}",
                    candidate,
                    DEFAULT_API_BASE_URL
                );
                return DEFAULT_API_BASE_URL;
            }

            return candidate.endsWith("/") ? candidate.substring(0, candidate.length() - 1) : candidate;
        } catch (RuntimeException ex) {
            log.warn(
                "Could not parse YOUTUBE_MUSIC_API_BASE_URL='{}'; falling back to {}",
                candidate,
                DEFAULT_API_BASE_URL
            );
            return DEFAULT_API_BASE_URL;
        }
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

    public List<SpotifyTrack> fetchPlaylistTracksById(String playlistId, String userAccessToken) {
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Playlist ID is required");
        }

        if (userAccessToken == null || userAccessToken.isBlank()) {
            throw new IllegalArgumentException("User access token is required for YouTube Music");
        }

        List<PlaylistEntry> playlistEntries = fetchPlaylistEntries(playlistId, userAccessToken.trim());
        if (playlistEntries.isEmpty()) {
            log.warn("YouTube playlist contained no entries for playlistId={}", playlistId);
            return List.of();
        }

        Map<String, VideoDetails> detailsByVideoId = fetchVideoDetails(playlistEntries, userAccessToken.trim());
        List<SpotifyTrack> tracks = new ArrayList<>();

        for (PlaylistEntry entry : playlistEntries) {
            VideoDetails details = detailsByVideoId.get(entry.videoId());
            String title = pickFirstNonBlank(
                details == null ? null : details.title(),
                entry.title(),
                entry.fallbackTitle()
            );
            String artist = pickFirstNonBlank(
                details == null ? null : details.artist(),
                entry.artist(),
                entry.channelTitle()
            );
            String album = details == null ? null : details.album();
            Long durationMs = details == null ? null : details.durationMs();

            if (title.isBlank() || artist.isBlank()) {
                continue;
            }

            tracks.add(new SpotifyTrack(title, artist, album, durationMs));
        }

        return tracks;
    }

    private List<PlaylistEntry> fetchPlaylistEntries(String playlistId, String userAccessToken) {
        List<PlaylistEntry> entries = new ArrayList<>();
        String nextPageToken = null;

        do {
            String url = buildPlaylistItemsUrl(playlistId, nextPageToken);
            JsonNode body = exchangeForJson(
                "youtube-music-playlist-items",
                url,
                HttpMethod.GET,
                new HttpEntity<>(buildAuthHeaders(userAccessToken))
            );

            if (body == null) {
                break;
            }

            JsonNode items = body.path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    JsonNode snippet = item.path("snippet");
                    JsonNode contentDetails = item.path("contentDetails");

                    String videoId = firstNonBlank(
                        snippet.path("resourceId").path("videoId").asText(""),
                        contentDetails.path("videoId").asText("")
                    );
                    if (videoId.isBlank()) {
                        continue;
                    }

                    String title = snippet.path("title").asText("");
                    String channelTitle = firstNonBlank(
                        snippet.path("videoOwnerChannelTitle").asText(""),
                        snippet.path("channelTitle").asText("")
                    );
                    String artist = inferArtistFromTitle(title, channelTitle);
                    String fallbackTitle = inferTitleFromSnippet(title);

                    entries.add(new PlaylistEntry(videoId, title, artist, channelTitle, fallbackTitle));
                }
            }

            nextPageToken = body.path("nextPageToken").asText("");
        } while (!nextPageToken.isBlank());

        return entries;
    }

    private Map<String, VideoDetails> fetchVideoDetails(List<PlaylistEntry> entries, String userAccessToken) {
        Map<String, VideoDetails> detailsByVideoId = new LinkedHashMap<>();

        for (int index = 0; index < entries.size(); index += PAGE_SIZE) {
            List<PlaylistEntry> batch = entries.subList(index, Math.min(entries.size(), index + PAGE_SIZE));
            String joinedIds = batch.stream().map(PlaylistEntry::videoId).distinct().reduce((left, right) -> left + "," + right).orElse("");
            if (joinedIds.isBlank()) {
                continue;
            }

            String url = UriComponentsBuilder
                .fromUriString(apiBaseUrl + "/videos")
                .queryParam("part", "snippet,contentDetails")
                .queryParam("id", joinedIds)
                .build()
                .toUriString();

            JsonNode body = exchangeForJson(
                "youtube-music-video-details",
                url,
                HttpMethod.GET,
                new HttpEntity<>(buildAuthHeaders(userAccessToken))
            );

            if (body == null) {
                continue;
            }

            JsonNode items = body.path("items");
            if (!items.isArray()) {
                continue;
            }

            for (JsonNode item : items) {
                String videoId = item.path("id").asText("");
                if (videoId.isBlank()) {
                    continue;
                }

                JsonNode snippet = item.path("snippet");
                JsonNode contentDetails = item.path("contentDetails");
                String title = snippet.path("title").asText("");
                String artist = firstNonBlank(
                    snippet.path("channelTitle").asText(""),
                    snippet.path("videoOwnerChannelTitle").asText("")
                );
                String album = snippet.path("album").asText(null);
                Long durationMs = parseDurationMillis(contentDetails.path("duration").asText(""));

                detailsByVideoId.put(videoId, new VideoDetails(title, artist, album, durationMs));
            }
        }

        return detailsByVideoId;
    }

    private String buildPlaylistItemsUrl(String playlistId, String nextPageToken) {
        UriComponentsBuilder builder = UriComponentsBuilder
            .fromUriString(apiBaseUrl + "/playlistItems")
            .queryParam("part", "snippet,contentDetails")
            .queryParam("playlistId", playlistId)
            .queryParam("maxResults", PAGE_SIZE)
            .queryParam("fields", "items(snippet(title,channelTitle,videoOwnerChannelTitle,resourceId/videoId),contentDetails/videoId),nextPageToken");

        if (nextPageToken != null && !nextPageToken.isBlank()) {
            builder.queryParam("pageToken", nextPageToken);
        }

        return builder.build().toUriString();
    }

    private HttpHeaders buildAuthHeaders(String userAccessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAccessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private String inferArtistFromTitle(String title, String fallbackArtist) {
        String normalizedTitle = Objects.requireNonNullElse(title, "").trim();
        if (normalizedTitle.isBlank()) {
            return Objects.requireNonNullElse(fallbackArtist, "").trim();
        }

        String[] separators = {" - ", " – ", " — ", " | "};
        for (String separator : separators) {
            int splitIndex = normalizedTitle.indexOf(separator);
            if (splitIndex > 0) {
                String left = normalizedTitle.substring(0, splitIndex).trim();
                String right = normalizedTitle.substring(splitIndex + separator.length()).trim();
                if (!left.isBlank() && !right.isBlank()) {
                    return left;
                }
            }
        }

        return Objects.requireNonNullElse(fallbackArtist, "").trim();
    }

    private String inferTitleFromSnippet(String title) {
        String normalized = Objects.requireNonNullElse(title, "").trim();
        if (normalized.isBlank()) {
            return normalized;
        }

        String[] separators = {" - ", " – ", " — ", " | "};
        for (String separator : separators) {
            int splitIndex = normalized.indexOf(separator);
            if (splitIndex > 0) {
                String left = normalized.substring(0, splitIndex).trim();
                String right = normalized.substring(splitIndex + separator.length()).trim();
                if (!left.isBlank() && !right.isBlank()) {
                    normalized = right;
                    break;
                }
            }
        }

        String[] suffixes = {
            "(Official Video)",
            "(Official Music Video)",
            "(Audio)",
            "(Lyrics)",
            "[Official Video]",
            "[Official Music Video]",
            "[Audio]",
            "[Lyrics]"
        };

        String stripped = normalized;
        for (String suffix : suffixes) {
            if (stripped.endsWith(suffix)) {
                stripped = stripped.substring(0, stripped.length() - suffix.length()).trim();
            }
        }

        return stripped.isBlank() ? normalized : stripped;
    }

    private Long parseDurationMillis(String isoDuration) {
        String normalized = Objects.requireNonNullElse(isoDuration, "").trim();
        if (normalized.isBlank()) {
            return null;
        }

        try {
            return Duration.parse(normalized).toMillis();
        } catch (RuntimeException ex) {
            log.warn("Unable to parse YouTube duration '{}'", normalized, ex);
            return null;
        }
    }

    private HttpHeaders buildJsonHeaders(String userAccessToken) {
        HttpHeaders headers = buildAuthHeaders(userAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode exchangeForJson(String operationName, String url, HttpMethod method, HttpEntity<?> requestEntity) {
        ResponseEntity<String> response = executeWithRetry(
            operationName,
            () -> restTemplate.exchange(url, method, requestEntity, String.class)
        );

        String payload = Objects.requireNonNullElse(response.getBody(), "").trim();
        MediaType contentType = response.getHeaders().getContentType();
        if (payload.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            String lower = payload.toLowerCase(Locale.ROOT);
            if (lower.startsWith("<!doctype html") || lower.startsWith("<html") || lower.contains("<head") || lower.contains("<body")) {
                String type = contentType == null ? "unknown" : contentType.toString();
                throw new IllegalStateException(
                    "YouTube source API returned HTML instead of JSON (" + type + ")."
                        + " Set YOUTUBE_MUSIC_API_BASE_URL to https://www.googleapis.com/youtube/v3.",
                    ex
                );
            }

            throw new IllegalStateException("YouTube source API returned malformed JSON payload", ex);
        }
    }

    private <T> T executeWithRetry(String operationName, Supplier<T> apiCall) {
        try {
            return apiCall.get();
        } catch (RuntimeException ex) {
            if (ex instanceof HttpStatusCodeException httpStatusCodeException) {
                String response = Objects.requireNonNullElse(httpStatusCodeException.getResponseBodyAsString(), "").toLowerCase(Locale.ROOT);
                if (httpStatusCodeException.getStatusCode().value() == 403 && response.contains("quota")) {
                    throw new IllegalStateException(
                        "YouTube API quota exceeded while reading the source playlist. Please try again later.",
                        ex
                    );
                }
                if (response.contains("access_token_scope_insufficient") || response.contains("insufficientpermissions")) {
                    throw new IllegalStateException(
                        "Google token is missing YouTube read permission. Reconnect Google and approve YouTube access, then retry.",
                        ex
                    );
                }
            }

            String message = Objects.requireNonNullElse(ex.getMessage(), "").toLowerCase(Locale.ROOT);
            if (message.contains("text/html") || message.contains("non-json") || message.contains("html")) {
                throw new IllegalStateException(
                    "YouTube source API returned HTML instead of JSON."
                        + " Set YOUTUBE_MUSIC_API_BASE_URL to https://www.googleapis.com/youtube/v3.",
                    ex
                );
            }

            if (ex instanceof ResourceAccessException) {
                log.warn("YouTube Music API request failed for operation={} reason={}", operationName, ex.getMessage());
            }

            throw ex;
        }
    }

    private String pickFirstNonBlank(String... values) {
        if (values == null) {
            return "";
        }

        for (String value : values) {
            String normalized = Objects.requireNonNullElse(value, "").trim();
            if (!normalized.isBlank()) {
                return normalized;
            }
        }

        return "";
    }

    private String firstNonBlank(String left, String right) {
        String normalizedLeft = Objects.requireNonNullElse(left, "").trim();
        if (!normalizedLeft.isBlank()) {
            return normalizedLeft;
        }

        String normalizedRight = Objects.requireNonNullElse(right, "").trim();
        return normalizedRight.isBlank() ? "" : normalizedRight;
    }

    private record PlaylistEntry(
        String videoId,
        String title,
        String artist,
        String channelTitle,
        String fallbackTitle
    ) {}

    private record VideoDetails(
        String title,
        String artist,
        String album,
        Long durationMs
    ) {}
}
