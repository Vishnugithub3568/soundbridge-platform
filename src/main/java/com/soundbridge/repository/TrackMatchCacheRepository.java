package com.soundbridge.repository;

import com.soundbridge.model.TrackMatchCache;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackMatchCacheRepository extends JpaRepository<TrackMatchCache, Long> {

    Optional<TrackMatchCache> findTopByUserIdAndNormalizedTrackTitleAndNormalizedArtist(
        UUID userId,
        String normalizedTrackTitle,
        String normalizedArtist
    );
}
