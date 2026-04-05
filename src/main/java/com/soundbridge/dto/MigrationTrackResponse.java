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
    String youtubeVideoId,
    String youtubeTitle,
    TrackMatchStatus matchStatus,
    Double matchScore,
    Double confidenceScore,
    String failureReason,
    String issueCategory,
    String recommendedAction
) {
    public MigrationTrackResponse(
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
        this(
            id,
            sourceTrackName,
            sourceArtistName,
            sourceAlbumName,
            targetTrackId,
            targetTrackUrl,
            targetTrackTitle,
            targetThumbnailUrl,
            null,
            null,
            matchStatus,
            confidenceScore,
            confidenceScore,
            failureReason,
            IssueCategoryClassifier.categorize(matchStatus, failureReason),
            IssueCategoryClassifier.recommendedAction(IssueCategoryClassifier.categorize(matchStatus, failureReason))
        );
    }

    public static MigrationTrackResponse from(MigrationTrack track) {
        Objects.requireNonNull(track, "track must not be null");
        String issueCategory = IssueCategoryClassifier.categorize(track.getMatchStatus(), track.getFailureReason());
        return new MigrationTrackResponse(
            track.getId(),
            track.getSourceTrackName(),
            track.getSourceArtistName(),
            track.getSourceAlbumName(),
            track.getTargetTrackId(),
            track.getTargetTrackUrl(),
            track.getTargetTrackTitle(),
            track.getTargetThumbnailUrl(),
            track.getYouTubeVideoId(),
            track.getYouTubeTitle(),
            track.getMatchStatus(),
            track.getMatchScore(),
            track.getConfidenceScore(),
            track.getFailureReason(),
            issueCategory,
            IssueCategoryClassifier.recommendedAction(issueCategory)
        );
    }
}
