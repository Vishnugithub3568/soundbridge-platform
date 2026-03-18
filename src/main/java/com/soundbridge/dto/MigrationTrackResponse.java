package com.soundbridge.dto;

import com.soundbridge.model.MigrationTrack;
import com.soundbridge.model.TrackMatchStatus;
import java.util.Objects;

public record MigrationTrackResponse(
    Long id,
    String sourceTrackName,
    String sourceArtistName,
    String sourceAlbumName,
    String targetTrackId,
    String targetTrackUrl,
    String targetTrackTitle,
    String targetThumbnailUrl,
    TrackMatchStatus matchStatus,
    Double confidenceScore,
    String failureReason
) {
    public static MigrationTrackResponse from(MigrationTrack track) {
        Objects.requireNonNull(track, "track must not be null");
        return new MigrationTrackResponse(
            track.getId(),
            track.getSourceTrackName(),
            track.getSourceArtistName(),
            track.getSourceAlbumName(),
            track.getTargetTrackId(),
            track.getTargetTrackUrl(),
            track.getTargetTrackTitle(),
            track.getTargetThumbnailUrl(),
            track.getMatchStatus(),
            track.getConfidenceScore(),
            track.getFailureReason()
        );
    }
}
