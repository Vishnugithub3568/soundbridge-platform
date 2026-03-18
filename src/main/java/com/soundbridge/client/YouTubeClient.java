package com.soundbridge.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class YouTubeClient {

    private final RestTemplate restTemplate;
    private final String apiBaseUrl;
    private final String apiKey;
    private final int maxResults;
    private final double threshold;

    public YouTubeClient(
        RestTemplateBuilder restTemplateBuilder,
        @Value("${youtube.api-base-url:https://www.googleapis.com/youtube/v3}") String apiBaseUrl,
        @Value("${youtube.api-key:}") String apiKey,
        @Value("${youtube.search.max-results:5}") int maxResults,
        @Value("${youtube.match.threshold:0.7}") double threshold
    ) {
        this.restTemplate = restTemplateBuilder.build();
        this.apiBaseUrl = apiBaseUrl;
        this.apiKey = apiKey;
        this.maxResults = maxResults;
        this.threshold = threshold;
    }

    public YouTubeMatch matchTrack(SpotifyTrack track) {
        if (track == null || track.name() == null || track.artist() == null) {
            return new YouTubeMatch(false, null, null, null, null, 0.0, false, "Invalid track input");
        }

        if (apiKey == null || apiKey.isBlank()) {
            return new YouTubeMatch(false, null, null, null, null, 0.0, false, "YouTube API key is not configured");
        }

        String query = track.name() + " " + track.artist();
        String url = UriComponentsBuilder
            .fromHttpUrl(apiBaseUrl + "/search")
            .queryParam("part", "snippet")
            .queryParam("type", "video")
            .queryParam("maxResults", Math.max(1, maxResults))
            .queryParam("q", query)
            .queryParam("key", apiKey)
            .build()
            .toUriString();

        ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.GET, null, JsonNode.class);
        JsonNode body = response.getBody();
        if (body == null) {
            return new YouTubeMatch(false, null, null, null, null, 0.0, false, "YouTube API returned empty response");
        }

        JsonNode items = body.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return new YouTubeMatch(false, null, null, null, null, 0.0, false, "No YouTube candidates found");
        }

        Candidate best = null;

        for (JsonNode item : items) {
            String videoId = item.path("id").path("videoId").asText("");
            String title = item.path("snippet").path("title").asText("");
            String channelTitle = item.path("snippet").path("channelTitle").asText("");
            String thumbnailUrl = extractThumbnail(item.path("snippet").path("thumbnails"));

            if (videoId.isBlank() || title.isBlank()) {
                continue;
            }

            double titleScore = similarity(track.name(), title);
            double artistScore = Math.max(similarity(track.artist(), channelTitle), similarity(track.artist(), title));
            double score = (0.75 * titleScore) + (0.25 * artistScore);

            Candidate candidate = new Candidate(videoId, title, thumbnailUrl, score);
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
        }

        if (best == null) {
            return new YouTubeMatch(false, null, null, null, null, 0.0, false, "No valid YouTube video candidates");
        }

        if (best.score < threshold) {
            return new YouTubeMatch(false, null, null, best.title, best.thumbnailUrl, best.score, false, "Best candidate below threshold");
        }

        return new YouTubeMatch(
            true,
            best.videoId,
            "https://music.youtube.com/watch?v=" + best.videoId,
            best.title,
            best.thumbnailUrl,
            best.score,
            best.score < 0.85,
            best.score < 0.85 ? "Partial confidence match" : null
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

    private double similarity(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isBlank() || b.isBlank()) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }

        Set<String> aTokens = new HashSet<>(Arrays.asList(a.split("\\s+")));
        Set<String> bTokens = new HashSet<>(Arrays.asList(b.split("\\s+")));
        aTokens.removeIf(String::isBlank);
        bTokens.removeIf(String::isBlank);

        if (aTokens.isEmpty() || bTokens.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(aTokens);
        intersection.retainAll(bTokens);

        Set<String> union = new HashSet<>(aTokens);
        union.addAll(bTokens);

        double jaccard = union.isEmpty() ? 0.0 : (double) intersection.size() / (double) union.size();
        double containment = (a.contains(b) || b.contains(a)) ? 1.0 : 0.0;

        return (0.8 * jaccard) + (0.2 * containment);
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
        private final String thumbnailUrl;
        private final double score;

        private Candidate(String videoId, String title, String thumbnailUrl, double score) {
            this.videoId = videoId;
            this.title = title;
            this.thumbnailUrl = thumbnailUrl;
            this.score = score;
        }
    }
}
