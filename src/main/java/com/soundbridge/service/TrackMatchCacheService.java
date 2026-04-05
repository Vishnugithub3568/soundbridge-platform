package com.soundbridge.service;

import com.soundbridge.client.SpotifyTrack;
import com.soundbridge.model.TrackMatchCache;
import com.soundbridge.repository.TrackMatchCacheRepository;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TrackMatchCacheService {

    private final TrackMatchCacheRepository trackMatchCacheRepository;

    public TrackMatchCacheService(TrackMatchCacheRepository trackMatchCacheRepository) {
        this.trackMatchCacheRepository = trackMatchCacheRepository;
    }

    public Optional<String> findCachedVideoId(UUID userId, SpotifyTrack sourceTrack) {
        if (userId == null || sourceTrack == null) {
            return Optional.empty();
        }

        String normalizedTitle = normalize(sourceTrack.name());
        String normalizedArtist = normalize(sourceTrack.artist());
        if (normalizedTitle.isBlank() || normalizedArtist.isBlank()) {
            return Optional.empty();
        }

        return trackMatchCacheRepository
            .findTopByUserIdAndNormalizedTrackTitleAndNormalizedArtist(userId, normalizedTitle, normalizedArtist)
            .map(TrackMatchCache::getYouTubeVideoId)
            .filter(videoId -> !videoId.isBlank());
    }

    public void storeMatch(UUID userId, SpotifyTrack sourceTrack, String youtubeVideoId) {
        if (userId == null || sourceTrack == null || youtubeVideoId == null || youtubeVideoId.isBlank()) {
            return;
        }

        String trackTitle = Objects.requireNonNullElse(sourceTrack.name(), "").trim();
        String artist = Objects.requireNonNullElse(sourceTrack.artist(), "").trim();
        String normalizedTitle = normalize(trackTitle);
        String normalizedArtist = normalize(artist);

        if (normalizedTitle.isBlank() || normalizedArtist.isBlank()) {
            return;
        }

        Optional<TrackMatchCache> existing = trackMatchCacheRepository
            .findTopByUserIdAndNormalizedTrackTitleAndNormalizedArtist(userId, normalizedTitle, normalizedArtist);

        TrackMatchCache cache = existing.orElseGet(TrackMatchCache::new);
        cache.setUserId(userId);
        cache.setTrackTitle(trackTitle);
        cache.setArtist(artist);
        cache.setNormalizedTrackTitle(normalizedTitle);
        cache.setNormalizedArtist(normalizedArtist);
        cache.setYouTubeVideoId(youtubeVideoId.trim());
        trackMatchCacheRepository.save(cache);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return Normalizer
            .normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
