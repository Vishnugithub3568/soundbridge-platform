package com.soundbridge.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class YouTubeClient {

    private static final Logger log = LoggerFactory.getLogger(YouTubeClient.class);
    private static final long MAX_BACKOFF_MS = 5000L;

    private final RestTemplate restTemplate;
    private final String apiBaseUrl;
    private final String apiKey;
    private final int maxResults;
    private final int retryMaxAttempts;
    private final long retryInitialBackoffMs;

    public YouTubeClient(
        RestTemplateBuilder restTemplateBuilder,
        @Value("${youtube.api-base-url:https://www.googleapis.com/youtube/v3}") String apiBaseUrl,
        @Value("${youtube.api-key:}") String apiKey,
        @Value("${youtube.search.max-results:5}") int maxResults,
        @Value("${api.retry.max-attempts:3}") int retryMaxAttempts,
        @Value("${api.retry.initial-backoff-ms:250}") long retryInitialBackoffMs
    ) {
        this.restTemplate = restTemplateBuilder.build();
        this.apiBaseUrl = apiBaseUrl;
        this.apiKey = apiKey;
        this.maxResults = Math.max(3, Math.min(5, maxResults));
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryInitialBackoffMs = Math.max(50L, retryInitialBackoffMs);
    }

    public List<YouTubeCandidate> searchCandidates(SpotifyTrack track) {
        if (track == null || track.name() == null || track.artist() == null) {
            return List.of();
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("YouTube API key is not configured, candidate search skipped");
            return List.of();
        }

        String query = track.name() + " " + track.artist();

        String url = UriComponentsBuilder
            .fromUriString(apiBaseUrl + "/search")
            .queryParam("part", "snippet")
            .queryParam("type", "video")
            .queryParam("maxResults", Math.max(1, maxResults))
            .queryParam("q", query)
            .queryParam("key", apiKey)
            .build()
            .toUriString();

        ResponseEntity<JsonNode> response = executeWithRetry(
            "youtube-search",
            () -> restTemplate.exchange(url, HttpMethod.GET, null, JsonNode.class)
        );
        JsonNode body = response.getBody();
        if (body == null) {
            return List.of();
        }

        JsonNode items = body.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return List.of();
        }

        List<YouTubeCandidate> candidates = new ArrayList<>();

        for (JsonNode item : items) {
            String videoId = item.path("id").path("videoId").asText("");
            String title = item.path("snippet").path("title").asText("");
            String channelTitle = item.path("snippet").path("channelTitle").asText("");
            String thumbnailUrl = extractThumbnail(item.path("snippet").path("thumbnails"));

            if (videoId.isBlank() || title.isBlank()) {
                continue;
            }

            YouTubeCandidate candidate = new YouTubeCandidate(
                videoId,
                title,
                channelTitle,
                thumbnailUrl
            );
            candidates.add(candidate);
        }

        return candidates;
    }

    public YouTubeMatch matchTrack(SpotifyTrack track) {
        List<YouTubeCandidate> candidates = searchCandidates(track);
        if (candidates.isEmpty()) {
            return new YouTubeMatch(false, null, null, null, null, 0.0, false, "No YouTube candidates found");
        }

        YouTubeCandidate first = candidates.get(0);
        return new YouTubeMatch(
            true,
            first.videoId(),
            "https://music.youtube.com/watch?v=" + first.videoId(),
            first.title(),
            first.thumbnailUrl(),
            0.0,
            false,
            null
        );
    }

    private String extractThumbnail(JsonNode thumbnailsNode) {
        if (thumbnailsNode == null || thumbnailsNode.isMissingNode()) {
            return null;
        }
        String high = thumbnailsNode.path("high").path("url").asText("");
        if (!high.isBlank()) {
            return high;
        }
        String medium = thumbnailsNode.path("medium").path("url").asText("");
        if (!medium.isBlank()) {
            return medium;
        }
        String def = thumbnailsNode.path("default").path("url").asText("");
        return def.isBlank() ? null : def;
    }

    private <T> T executeWithRetry(String operationName, Supplier<T> apiCall) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            try {
                return apiCall.get();
            } catch (RuntimeException ex) {
                last = ex;
                boolean retryable = isRetryable(ex);
                if (!retryable || attempt == retryMaxAttempts) {
                    throw ex;
                }

                long delayMs = calculateBackoffMillis(attempt);
                log.warn(
                    "YouTube API retry | operation={} attempt={}/{} delayMs={} reason={}",
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

}
