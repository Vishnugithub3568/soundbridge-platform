package com.soundbridge.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
    private static final Set<String> MISMATCH_KEYWORDS = Set.of("remix", "live", "cover");
    private static final long MAX_BACKOFF_MS = 5000L;

    private final RestTemplate restTemplate;
    private final String apiBaseUrl;
    private final String apiKey;
    private final int maxResults;
    private final double threshold;
    private final int retryMaxAttempts;
    private final long retryInitialBackoffMs;

    public YouTubeClient(
        RestTemplateBuilder restTemplateBuilder,
        @Value("${youtube.api-base-url:https://www.googleapis.com/youtube/v3}") String apiBaseUrl,
        @Value("${youtube.api-key:}") String apiKey,
        @Value("${youtube.search.max-results:5}") int maxResults,
        @Value("${youtube.match.threshold:0.65}") double threshold,
        @Value("${api.retry.max-attempts:3}") int retryMaxAttempts,
        @Value("${api.retry.initial-backoff-ms:250}") long retryInitialBackoffMs
    ) {
        this.restTemplate = restTemplateBuilder.build();
        this.apiBaseUrl = apiBaseUrl;
        this.apiKey = apiKey;
        this.maxResults = maxResults;
        this.threshold = threshold;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryInitialBackoffMs = Math.max(50L, retryInitialBackoffMs);
    }

    public YouTubeMatch matchTrack(SpotifyTrack track) {
        if (track == null || track.name() == null || track.artist() == null) {
            return new YouTubeMatch(false, null, null, null, null, 0.0, false, "Invalid track input");
        }

        if (apiKey == null || apiKey.isBlank()) {
            return new YouTubeMatch(false, null, null, null, null, 0.0, false, "YouTube API key is not configured");
        }

        String query = track.name() + " " + track.artist();
        String normalizedSourceTitle = normalize(track.name());
        String normalizedSourceArtist = normalize(track.artist());

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
            return new YouTubeMatch(false, null, null, null, null, 0.0, false, "YouTube API returned empty response");
        }

        JsonNode items = body.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return new YouTubeMatch(false, null, null, null, null, 0.0, false, "No YouTube candidates found");
        }

        Candidate best = null;
        List<Candidate> candidates = new ArrayList<>();

        for (JsonNode item : items) {
            String videoId = item.path("id").path("videoId").asText("");
            String title = item.path("snippet").path("title").asText("");
            String channelTitle = item.path("snippet").path("channelTitle").asText("");
            String thumbnailUrl = extractThumbnail(item.path("snippet").path("thumbnails"));

            if (videoId.isBlank() || title.isBlank()) {
                continue;
            }

            String normalizedCandidateTitle = normalize(title);
            String normalizedCandidateArtist = normalize(channelTitle);

            double titleScore = ratioSimilarity(normalizedSourceTitle, normalizedCandidateTitle);
            double artistScore = Math.max(
                ratioSimilarity(normalizedSourceArtist, normalizedCandidateArtist),
                ratioSimilarity(normalizedSourceArtist, normalizedCandidateTitle)
            );

            double penalty = mismatchPenalty(
                normalizedSourceTitle,
                normalizedCandidateTitle,
                normalizedSourceArtist,
                normalizedCandidateArtist
            );

            double rawScore = (0.7 * titleScore) + (0.3 * artistScore);
            double finalScore = clamp(rawScore - penalty);

            Candidate candidate = new Candidate(
                videoId,
                title,
                channelTitle,
                thumbnailUrl,
                titleScore,
                artistScore,
                penalty,
                finalScore
            );
            candidates.add(candidate);

            log.debug(
                "YouTube scoring | source='{}' artist='{}' | candidateId={} title='{}' channel='{}' titleScore={} artistScore={} penalty={} finalScore={}",
                track.name(),
                track.artist(),
                videoId,
                title,
                channelTitle,
                round3(titleScore),
                round3(artistScore),
                round3(penalty),
                round3(finalScore)
            );
        }

        if (candidates.isEmpty()) {
            return new YouTubeMatch(false, null, null, null, null, 0.0, false, "No valid YouTube video candidates");
        }

        candidates.sort(
            Comparator
                .comparingDouble(Candidate::score).reversed()
                .thenComparing(Candidate::videoId)
                .thenComparing(Candidate::title)
        );
        best = candidates.get(0);

        log.debug(
            "YouTube best candidate | source='{}' artist='{}' | bestId={} bestTitle='{}' titleScore={} artistScore={} penalty={} finalScore={} threshold={}",
            track.name(),
            track.artist(),
            best.videoId,
            best.title,
            round3(best.titleScore),
            round3(best.artistScore),
            round3(best.penalty),
            round3(best.score),
            round3(threshold)
        );

        if (best.score < threshold) {
            return new YouTubeMatch(
                false,
                null,
                null,
                best.title,
                best.thumbnailUrl,
                best.score,
                false,
                "FAILED: best candidate below threshold"
            );
        }

        return new YouTubeMatch(
            true,
            best.videoId,
            "https://music.youtube.com/watch?v=" + best.videoId,
            best.title,
            best.thumbnailUrl,
            best.score,
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

    private double ratioSimilarity(String a, String b) {
        if (a.isBlank() || b.isBlank()) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }

        int distance = levenshteinDistance(a, b);
        int maxLength = Math.max(a.length(), b.length());
        if (maxLength == 0) {
            return 1.0;
        }
        return clamp(1.0 - ((double) distance / (double) maxLength));
    }

    private int levenshteinDistance(String left, String right) {
        int leftLen = left.length();
        int rightLen = right.length();
        int[] previous = new int[rightLen + 1];
        int[] current = new int[rightLen + 1];

        for (int j = 0; j <= rightLen; j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= leftLen; i++) {
            current[0] = i;
            char leftChar = left.charAt(i - 1);
            for (int j = 1; j <= rightLen; j++) {
                int substitutionCost = leftChar == right.charAt(j - 1) ? 0 : 1;
                int insertion = current[j - 1] + 1;
                int deletion = previous[j] + 1;
                int substitution = previous[j - 1] + substitutionCost;
                current[j] = Math.min(Math.min(insertion, deletion), substitution);
            }

            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[rightLen];
    }

    private double mismatchPenalty(
        String sourceTitle,
        String candidateTitle,
        String sourceArtist,
        String candidateArtist
    ) {
        double penalty = 0.0;
        Set<String> sourceTokens = tokenSet(sourceTitle + " " + sourceArtist);
        Set<String> candidateTokens = tokenSet(candidateTitle + " " + candidateArtist);

        for (String keyword : MISMATCH_KEYWORDS) {
            boolean sourceHas = sourceTokens.contains(keyword);
            boolean candidateHas = candidateTokens.contains(keyword);
            if (sourceHas != candidateHas) {
                penalty += 0.15;
            }
        }

        return Math.min(0.45, penalty);
    }

    private Set<String> tokenSet(String value) {
        Set<String> tokens = new TreeSet<>();
        if (value == null || value.isBlank()) {
            return tokens;
        }
        for (String token : value.split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
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

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
        return Objects.requireNonNullElse(normalized, "");
    }

    private static final class Candidate {
        private final String videoId;
        private final String title;
        private final String channelTitle;
        private final String thumbnailUrl;
        private final double titleScore;
        private final double artistScore;
        private final double penalty;
        private final double score;

        private Candidate(
            String videoId,
            String title,
            String channelTitle,
            String thumbnailUrl,
            double titleScore,
            double artistScore,
            double penalty,
            double score
        ) {
            this.videoId = videoId;
            this.title = title;
            this.channelTitle = channelTitle;
            this.thumbnailUrl = thumbnailUrl;
            this.titleScore = titleScore;
            this.artistScore = artistScore;
            this.penalty = penalty;
            this.score = score;
        }

        private String videoId() {
            return videoId;
        }

        private String title() {
            return title;
        }

        private double score() {
            return score;
        }
    }
}
