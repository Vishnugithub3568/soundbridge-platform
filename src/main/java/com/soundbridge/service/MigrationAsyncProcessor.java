package com.soundbridge.service;

import com.soundbridge.client.SpotifyClient;
import com.soundbridge.client.SpotifyTrack;
import com.soundbridge.client.YouTubeCandidate;
import com.soundbridge.client.YouTubeClient;
import com.soundbridge.client.YouTubeMusicClient;
import com.soundbridge.exception.QuotaExceededException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Comparator;
import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import com.soundbridge.model.MigrationTrack;
import com.soundbridge.model.TrackMatchStatus;
import java.util.Locale;
import java.time.Instant;
import com.soundbridge.repository.MigrationJobRepository;
import com.soundbridge.repository.MigrationTrackRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;

@Service
@SuppressWarnings("null")
public class MigrationAsyncProcessor {

    private static final Logger log = LoggerFactory.getLogger(MigrationAsyncProcessor.class);
    private static final Set<String> INDICATOR_KEYWORDS = Set.of(
        "remix", "live", "cover", "karaoke", "explicit", "clean", "acoustic", "instrumental"
    );
    private static final String LOW_CONFIDENCE_FALLBACK_REASON =
        "LOW_CONFIDENCE_FALLBACK: best candidate accepted to keep migration reliable";
    private static final String NO_CANDIDATE_FALLBACK_REASON =
        "SAFE_FALLBACK: no candidates returned, linked YouTube Music search result";
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final int BATCH_SIZE = 20;
    private static final int QUOTA_UNITS_PER_SPOTIFY_TO_YOUTUBE_TRACK = 101;
    private static final int YOUTUBE_PLAYLIST_CREATE_UNITS = 50;
    private static final String QUOTA_PAUSED_REASON =
        "YouTube API quota exceeded. Migration is paused and can be resumed after quota reset.";
    private static final Pattern BRACKETED_SEGMENTS_PATTERN = Pattern.compile("\\([^)]*\\)|\\[[^]]*]|\\{[^}]*}");
    private static final Pattern FEATURING_PATTERN = Pattern.compile("\\b(feat|ft|featuring|with)\\b");
    private static final Pattern NOISE_TERMS_PATTERN = Pattern.compile(
        "\\b(official|video|audio|lyrics?|lyric|version|remaster(ed)?|mono|stereo|topic|hq|hd)\\b"
    );

    private final MigrationJobRepository jobRepository;
    private final MigrationTrackRepository trackRepository;
    private final SpotifyClient spotifyClient;
    private final YouTubeClient youTubeClient;
    private final YouTubeMusicClient youTubeMusicClient;
    private final TrackMatchCacheService trackMatchCacheService;
    private final double matchThreshold;
    private final double retryMatchThreshold;
    private final double titleWeight;
    private final double artistWeight;
    private final double indicatorMismatchPenalty;
    private final double indicatorMatchBonus;
    private final double artistTitleCrossWeight;

    @Autowired
    public MigrationAsyncProcessor(
        MigrationJobRepository jobRepository,
        MigrationTrackRepository trackRepository,
        SpotifyClient spotifyClient,
        YouTubeClient youTubeClient,
        YouTubeMusicClient youTubeMusicClient,
        TrackMatchCacheService trackMatchCacheService,
        @Value("${youtube.match.threshold:0.65}") double matchThreshold,
        @Value("${youtube.retry.match.threshold:0.40}") double retryMatchThreshold,
        @Value("${youtube.match.title.weight:0.62}") double configuredTitleWeight,
        @Value("${youtube.match.artist.weight:0.38}") double configuredArtistWeight,
        @Value("${youtube.match.indicator.mismatch.penalty:0.18}") double indicatorMismatchPenalty,
        @Value("${youtube.match.indicator.match.bonus:0.04}") double indicatorMatchBonus,
        @Value("${youtube.match.artist.title.cross-weight:0.55}") double artistTitleCrossWeight
    ) {
        this.jobRepository = jobRepository;
        this.trackRepository = trackRepository;
        this.spotifyClient = spotifyClient;
        this.youTubeClient = youTubeClient;
        this.youTubeMusicClient = youTubeMusicClient;
        this.trackMatchCacheService = trackMatchCacheService;
        this.matchThreshold = matchThreshold;
        this.retryMatchThreshold = retryMatchThreshold;
        double sanitizedTitleWeight = Math.max(0.0, configuredTitleWeight);
        double sanitizedArtistWeight = Math.max(0.0, configuredArtistWeight);
        double totalWeight = sanitizedTitleWeight + sanitizedArtistWeight;
        if (totalWeight <= 0.0) {
            this.titleWeight = 0.62;
            this.artistWeight = 0.38;
        } else {
            this.titleWeight = sanitizedTitleWeight / totalWeight;
            this.artistWeight = sanitizedArtistWeight / totalWeight;
        }
        this.indicatorMismatchPenalty = Math.max(0.0, indicatorMismatchPenalty);
        this.indicatorMatchBonus = Math.max(0.0, indicatorMatchBonus);
        this.artistTitleCrossWeight = clamp(artistTitleCrossWeight);
    }

    public MigrationAsyncProcessor(
        MigrationJobRepository jobRepository,
        MigrationTrackRepository trackRepository,
        SpotifyClient spotifyClient,
        YouTubeClient youTubeClient,
        YouTubeMusicClient youTubeMusicClient,
        TrackMatchCacheService trackMatchCacheService
    ) {
        this(
            jobRepository,
            trackRepository,
            spotifyClient,
            youTubeClient,
            youTubeMusicClient,
            trackMatchCacheService,
            0.65,
            0.40,
            0.62,
            0.38,
            0.18,
            0.04,
            0.55
        );
    }

    public void processMigration(UUID jobId) {
        MigrationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }

        try {
            job.setStatus(JobStatus.RUNNING);
            job.setPausedReason(null);
            job.setNextRetryTime(null);
            jobRepository.saveAndFlush(job);

            boolean spotifyDestination = isSpotifyDestination(job);
            String sourceAccessToken = spotifyDestination
                ? Objects.requireNonNullElse(job.getGoogleAccessToken(), "").trim()
                : Objects.requireNonNullElse(job.getSpotifyAccessToken(), "").trim();
            String targetAccessToken = spotifyDestination
                ? Objects.requireNonNullElse(job.getSpotifyAccessToken(), "").trim()
                : Objects.requireNonNullElse(job.getGoogleAccessToken(), "").trim();

            List<SpotifyTrack> sourceTracks = fetchSourceTracks(job, spotifyDestination, sourceAccessToken);
            if (sourceTracks == null) {
                sourceTracks = List.of();
            }

            job.setQuotaUnitsEstimated(estimateQuotaUnits(spotifyDestination, sourceTracks.size()));

            String targetPlaylistId = Objects.requireNonNullElse(job.getTargetPlaylistId(), "").trim();
            if (targetPlaylistId.isBlank()) {
                targetPlaylistId = createTargetPlaylist(job, spotifyDestination, targetAccessToken);
                job.setTargetPlaylistId(targetPlaylistId);
                job.setTargetPlaylistUrl(buildTargetPlaylistUrl(spotifyDestination, targetPlaylistId));
            }

            job.setTotalTracks(sourceTracks.size());
            int startIndex = Math.max(0, Math.min(job.getLastProcessedIndex(), sourceTracks.size()));

            if (startIndex == 0) {
                job.setMatchedTracks(0);
                job.setFailedTracks(0);
            }
            jobRepository.saveAndFlush(job);

            for (int batchStart = startIndex; batchStart < sourceTracks.size(); batchStart += BATCH_SIZE) {
                int batchEnd = Math.min(sourceTracks.size(), batchStart + BATCH_SIZE);

                for (int trackIndex = batchStart; trackIndex < batchEnd; trackIndex++) {
                    SpotifyTrack sourceTrack = sourceTracks.get(trackIndex);
                    if (sourceTrack == null) {
                        job.setFailedTracks(job.getFailedTracks() + 1);
                        job.setLastProcessedIndex(trackIndex + 1);
                        jobRepository.saveAndFlush(job);
                        continue;
                    }

                    MigrationTrack migrationTrack = new MigrationTrack();
                    migrationTrack.setJob(job);
                    migrationTrack.setSourceTrackName(Objects.requireNonNullElse(sourceTrack.name(), "Unknown Track"));
                    migrationTrack.setSourceArtistName(Objects.requireNonNullElse(sourceTrack.artist(), "Unknown Artist"));
                    migrationTrack.setSourceAlbumName(sourceTrack.album());

                    boolean matchedTrack = spotifyDestination
                        ? processSpotifyDestinationTrack(migrationTrack, sourceTrack, targetPlaylistId, targetAccessToken)
                        : processYouTubeDestinationTrack(job, migrationTrack, sourceTrack, targetPlaylistId, targetAccessToken);

                    if (matchedTrack) {
                        job.setMatchedTracks(job.getMatchedTracks() + 1);
                    } else {
                        job.setFailedTracks(job.getFailedTracks() + 1);
                    }

                    trackRepository.save(migrationTrack);
                    job.setLastProcessedIndex(trackIndex + 1);
                    jobRepository.saveAndFlush(job);

                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Migration processing interrupted", interruptedException);
                    }
                }
            }

            job.setStatus(JobStatus.COMPLETED);
            job.setPausedReason(null);
            job.setNextRetryTime(null);
            jobRepository.saveAndFlush(job);
        } catch (QuotaExceededException ex) {
            job.setStatus(JobStatus.QUOTA_PAUSED);
            job.setPausedReason(QUOTA_PAUSED_REASON);
            job.setNextRetryTime(Instant.now().plusSeconds(24L * 60L * 60L));
            jobRepository.saveAndFlush(job);
            log.warn("Migration job {} paused because YouTube quota was exhausted", jobId);
        } catch (Exception ex) {
            int unresolved = Math.max(0, job.getTotalTracks() - job.getLastProcessedIndex());
            if (unresolved > 0) {
                job.setFailedTracks(job.getFailedTracks() + unresolved);
            }

            if (job.getTotalTracks() == 0 && job.getFailedTracks() == 0) {
                MigrationTrack setupFailure = new MigrationTrack();
                setupFailure.setJob(job);
                setupFailure.setSourceTrackName("Migration Setup Failed");
                setupFailure.setSourceArtistName("SoundBridge");
                markTrackAsFailed(setupFailure, "FAILED: " + summarizeError(ex));
                trackRepository.save(setupFailure);
                job.setTotalTracks(1);
                job.setFailedTracks(1);
            }

            job.setStatus(JobStatus.FAILED);
            jobRepository.saveAndFlush(job);
            log.error("Migration job {} failed during processing", jobId, ex);
        }
    }

    public void retryFailedTracks(UUID jobId) {
        MigrationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }

        try {
            job.setStatus(JobStatus.RUNNING);
            job.setPausedReason(null);
            job.setNextRetryTime(null);
            jobRepository.saveAndFlush(job);

            boolean spotifyDestination = isSpotifyDestination(job);
            String sourceAccessToken = spotifyDestination
                ? Objects.requireNonNullElse(job.getGoogleAccessToken(), "").trim()
                : Objects.requireNonNullElse(job.getSpotifyAccessToken(), "").trim();
            String targetAccessToken = spotifyDestination
                ? Objects.requireNonNullElse(job.getSpotifyAccessToken(), "").trim()
                : Objects.requireNonNullElse(job.getGoogleAccessToken(), "").trim();

            String targetPlaylistId = Objects.requireNonNullElse(job.getTargetPlaylistId(), "").trim();
            if (targetPlaylistId.isBlank()) {
                targetPlaylistId = createTargetPlaylist(job, spotifyDestination, targetAccessToken);
                job.setTargetPlaylistId(targetPlaylistId);
                job.setTargetPlaylistUrl(buildTargetPlaylistUrl(spotifyDestination, targetPlaylistId));
                jobRepository.saveAndFlush(job);
            }

            List<MigrationTrack> allTracks = new ArrayList<>(trackRepository.findByJobIdOrderByIdAsc(jobId));
            for (MigrationTrack track : allTracks) {
                if (!isRetryableTrackStatus(track.getMatchStatus())) {
                    continue;
                }

                SpotifyTrack sourceTrack = new SpotifyTrack(
                    Objects.requireNonNullElse(track.getSourceTrackName(), ""),
                    Objects.requireNonNullElse(track.getSourceArtistName(), ""),
                    track.getSourceAlbumName(),
                    null
                );

                boolean matchedTrack = spotifyDestination
                    ? processSpotifyDestinationTrack(track, sourceTrack, targetPlaylistId, targetAccessToken)
                    : processYouTubeDestinationTrack(job, track, sourceTrack, targetPlaylistId, targetAccessToken);

                if (matchedTrack && track.getMatchStatus() == TrackMatchStatus.MATCHED) {
                    track.setFailureReason(null);
                }

                trackRepository.save(track);

                try {
                    Thread.sleep(150);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Failed-track retry interrupted", interruptedException);
                }
            }

            refreshJobCounters(job);
            job.setStatus(JobStatus.COMPLETED);
            jobRepository.saveAndFlush(job);
        } catch (QuotaExceededException ex) {
            refreshJobCounters(job);
            job.setStatus(JobStatus.QUOTA_PAUSED);
            job.setPausedReason(QUOTA_PAUSED_REASON);
            job.setNextRetryTime(Instant.now().plusSeconds(24L * 60L * 60L));
            jobRepository.saveAndFlush(job);
            log.warn("Migration job {} paused during retry because YouTube quota was exhausted", jobId);
        } catch (Exception ex) {
            refreshJobCounters(job);
            job.setStatus(JobStatus.FAILED);
            jobRepository.saveAndFlush(job);
            log.error("Migration job {} failed during retry of failed tracks", jobId, ex);
        }
    }

    private void refreshJobCounters(MigrationJob job) {
        List<MigrationTrack> tracks = trackRepository.findByJobIdOrderByIdAsc(job.getId());
        int matched = (int) tracks
            .stream()
            .filter(track -> track.getMatchStatus() == TrackMatchStatus.MATCHED || track.getMatchStatus() == TrackMatchStatus.PARTIAL)
            .count();
        int failed = (int) tracks
            .stream()
            .filter(track -> track.getMatchStatus() == TrackMatchStatus.FAILED || track.getMatchStatus() == TrackMatchStatus.NOT_FOUND)
            .count();

        job.setTotalTracks(tracks.size());
        job.setMatchedTracks(matched);
        job.setFailedTracks(failed);
    }

    private boolean applyCandidateMatch(
        MigrationTrack migrationTrack,
        SpotifyTrack sourceTrack,
        List<YouTubeCandidate> candidates,
        double threshold
    ) {
        if (candidates == null || candidates.isEmpty()) {
            applySearchFallbackMatch(migrationTrack, sourceTrack, NO_CANDIDATE_FALLBACK_REASON);
            return true;
        }

        ScoredCandidate best = findBestCandidate(sourceTrack, candidates);
        migrationTrack.setConfidenceScore(best.score());
        migrationTrack.setMatchScore(best.score());
        migrationTrack.setTargetTrackTitle(best.candidate().title());
        migrationTrack.setTargetThumbnailUrl(best.candidate().thumbnailUrl());
        migrationTrack.setYouTubeTitle(best.candidate().title());
        migrationTrack.setYouTubeVideoId(best.candidate().videoId());

        if (best.score() >= threshold) {
            migrationTrack.setTargetTrackId(best.candidate().videoId());
            migrationTrack.setTargetTrackUrl("https://music.youtube.com/watch?v=" + best.candidate().videoId());
            migrationTrack.setMatchStatus(TrackMatchStatus.MATCHED);
            migrationTrack.setFailureReason(null);
            return true;
        }

        migrationTrack.setTargetTrackId(best.candidate().videoId());
        migrationTrack.setTargetTrackUrl("https://music.youtube.com/watch?v=" + best.candidate().videoId());
        migrationTrack.setMatchStatus(TrackMatchStatus.MATCHED);
        migrationTrack.setFailureReason(LOW_CONFIDENCE_FALLBACK_REASON);
        return true;
    }

    private void applySearchFallbackMatch(MigrationTrack migrationTrack, SpotifyTrack sourceTrack, String reason) {
        String searchQuery = (Objects.requireNonNullElse(sourceTrack.name(), "") + " "
            + Objects.requireNonNullElse(sourceTrack.artist(), "")).trim();
        String encoded = URLEncoder.encode(searchQuery, StandardCharsets.UTF_8);

        migrationTrack.setMatchStatus(TrackMatchStatus.MATCHED);
        migrationTrack.setConfidenceScore(0.0);
        migrationTrack.setMatchScore(0.0);
        migrationTrack.setTargetTrackId("search:" + encoded);
        migrationTrack.setTargetTrackUrl("https://music.youtube.com/search?q=" + encoded);
        migrationTrack.setTargetTrackTitle(searchQuery.isBlank() ? "YouTube Music Search" : searchQuery + " (Search)");
        migrationTrack.setTargetThumbnailUrl(null);
        migrationTrack.setYouTubeTitle(migrationTrack.getTargetTrackTitle());
        migrationTrack.setYouTubeVideoId(null);
        migrationTrack.setFailureReason(reason);
    }

    private void markTrackAsFailed(MigrationTrack migrationTrack, String reason) {
        migrationTrack.setMatchStatus(TrackMatchStatus.FAILED);
        migrationTrack.setConfidenceScore(0.0);
        migrationTrack.setMatchScore(0.0);
        migrationTrack.setFailureReason(reason);
        migrationTrack.setTargetTrackId(null);
        migrationTrack.setTargetTrackUrl(null);
    }

    private void markTrackAsPartial(MigrationTrack migrationTrack, String reason) {
        migrationTrack.setMatchStatus(TrackMatchStatus.PARTIAL);
        migrationTrack.setFailureReason(reason);
    }

    private boolean isSpotifyDestination(MigrationJob job) {
        return "SPOTIFY".equalsIgnoreCase(Objects.requireNonNullElse(job.getTargetPlatform(), ""));
    }

    private List<SpotifyTrack> fetchSourceTracks(MigrationJob job, boolean spotifyDestination, String sourceAccessToken) {
        if (spotifyDestination) {
            return youTubeMusicClient.fetchPlaylistTracks(job.getSourcePlaylistUrl(), sourceAccessToken);
        }

        return spotifyClient.fetchPlaylistTracks(job.getSourcePlaylistUrl(), sourceAccessToken);
    }

    private String createTargetPlaylist(MigrationJob job, boolean spotifyDestination, String targetAccessToken) {
        if (spotifyDestination) {
            if (targetAccessToken.isBlank()) {
                throw new IllegalStateException("Spotify login is required to create Spotify playlist.");
            }

            return spotifyClient.createPlaylist(
                targetAccessToken,
                buildPlaylistTitle(),
                "Migrated from YouTube Music by SoundBridge. Source: " + job.getSourcePlaylistUrl()
            );
        }

        if (targetAccessToken.isBlank()) {
            throw new IllegalStateException("Google login is required to export tracks into a YouTube playlist.");
        }

        return youTubeClient.createPlaylist(
            targetAccessToken,
            buildPlaylistTitle(),
            "Migrated from Spotify by SoundBridge. Source: " + job.getSourcePlaylistUrl()
        );
    }

    private String buildTargetPlaylistUrl(boolean spotifyDestination, String targetPlaylistId) {
        if (spotifyDestination) {
            return "https://open.spotify.com/playlist/" + targetPlaylistId;
        }

        return "https://music.youtube.com/playlist?list=" + targetPlaylistId;
    }

    private boolean processYouTubeDestinationTrack(
        MigrationJob job,
        MigrationTrack migrationTrack,
        SpotifyTrack sourceTrack,
        String targetPlaylistId,
        String googleAccessToken
    ) {
        try {
            String videoId = trackMatchCacheService.findCachedVideoId(job.getUserId(), sourceTrack).orElse("").trim();
            if (videoId.isBlank()) {
                List<YouTubeCandidate> candidates = youTubeClient.searchCandidates(sourceTrack);
                boolean matchedTrack = applyCandidateMatch(migrationTrack, sourceTrack, candidates, matchThreshold);
                if (!matchedTrack) {
                    return false;
                }
                videoId = Objects.requireNonNullElse(migrationTrack.getYouTubeVideoId(), "").trim();
            } else {
                migrationTrack.setConfidenceScore(1.0);
                migrationTrack.setMatchScore(1.0);
                migrationTrack.setTargetTrackId(videoId);
                migrationTrack.setTargetTrackUrl("https://music.youtube.com/watch?v=" + videoId);
                migrationTrack.setTargetTrackTitle(Objects.requireNonNullElse(sourceTrack.name(), "") + " (Cached Match)");
                migrationTrack.setYouTubeTitle(migrationTrack.getTargetTrackTitle());
                migrationTrack.setYouTubeVideoId(videoId);
                migrationTrack.setMatchStatus(TrackMatchStatus.MATCHED);
                migrationTrack.setFailureReason(null);
            }

            if (videoId.isBlank()) {
                markTrackAsPartial(
                    migrationTrack,
                    "PARTIAL: matched for review, but no direct YouTube video id was available for playlist export"
                );
                return true;
            }

            trackMatchCacheService.storeMatch(job.getUserId(), sourceTrack, videoId);

            try {
                youTubeClient.addVideoToPlaylist(googleAccessToken, targetPlaylistId, videoId);
                migrationTrack.setMatchStatus(TrackMatchStatus.MATCHED);
                migrationTrack.setFailureReason(null);
            } catch (RuntimeException ex) {
                if (isQuotaExceeded(ex)) {
                    throw new QuotaExceededException(QUOTA_PAUSED_REASON, ex);
                }
                markTrackAsPartial(
                    migrationTrack,
                    "PARTIAL: matched for review, but could not add track to YouTube playlist: " + summarizeError(ex)
                );
            }

            return true;
        } catch (RuntimeException ex) {
            if (isQuotaExceeded(ex)) {
                throw new QuotaExceededException(QUOTA_PAUSED_REASON, ex);
            }
            applySearchFallbackMatch(
                migrationTrack,
                sourceTrack,
                "SAFE_FALLBACK: YouTube lookup unavailable, linked search result"
            );
            log.warn(
                "Track-level lookup failed for source='{}' artist='{}' reason={}",
                sourceTrack.name(),
                sourceTrack.artist(),
                summarizeError(ex)
            );
            return true;
        }
    }

    private int estimateQuotaUnits(boolean spotifyDestination, int sourceTrackCount) {
        if (spotifyDestination) {
            return Math.max(0, sourceTrackCount * 2);
        }

        return YOUTUBE_PLAYLIST_CREATE_UNITS + (Math.max(0, sourceTrackCount) * QUOTA_UNITS_PER_SPOTIFY_TO_YOUTUBE_TRACK);
    }

    private boolean isQuotaExceeded(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = Objects.requireNonNullElse(current.getMessage(), "").toLowerCase(Locale.ROOT);
            if (message.contains("quota") && (message.contains("exceeded") || message.contains("exhausted"))) {
                return true;
            }

            if (current instanceof HttpStatusCodeException statusCodeException) {
                String body = Objects.requireNonNullElse(statusCodeException.getResponseBodyAsString(), "").toLowerCase(Locale.ROOT);
                if (statusCodeException.getStatusCode().value() == 403 && body.contains("quota")) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }

    private boolean processSpotifyDestinationTrack(
        MigrationTrack migrationTrack,
        SpotifyTrack sourceTrack,
        String targetPlaylistId,
        String spotifyAccessToken
    ) {
        try {
            List<SpotifyClient.SpotifySearchCandidate> candidates = spotifyClient.searchTrackCandidates(
                sourceTrack.name(),
                sourceTrack.artist(),
                spotifyAccessToken
            );
            if (candidates.isEmpty()) {
                markTrackAsFailed(migrationTrack, "FAILED: No Spotify match found for source track");
                return false;
            }

            SpotifyClient.SpotifySearchCandidate bestCandidate = candidates.get(0);
            migrationTrack.setMatchStatus(TrackMatchStatus.MATCHED);
            migrationTrack.setConfidenceScore(1.0);
            migrationTrack.setMatchScore(1.0);
            migrationTrack.setTargetTrackId(bestCandidate.id());
            migrationTrack.setTargetTrackUrl(bestCandidate.externalUrl().isBlank()
                ? "https://open.spotify.com/track/" + bestCandidate.id()
                : bestCandidate.externalUrl());
            migrationTrack.setTargetTrackTitle(bestCandidate.name());
            migrationTrack.setTargetThumbnailUrl(bestCandidate.thumbnailUrl());
            migrationTrack.setYouTubeTitle(null);
            migrationTrack.setYouTubeVideoId(null);
            migrationTrack.setFailureReason(null);

            spotifyClient.addTrackToPlaylist(spotifyAccessToken, targetPlaylistId, bestCandidate.uri());
            return true;
        } catch (RuntimeException ex) {
            markTrackAsPartial(
                migrationTrack,
                "PARTIAL: matched for review, but could not add track to Spotify playlist: " + summarizeError(ex)
            );
            log.warn(
                "Spotify destination lookup failed for source='{}' artist='{}' reason={}",
                sourceTrack.name(),
                sourceTrack.artist(),
                summarizeError(ex)
            );
            return true;
        }
    }

    private String summarizeError(Exception ex) {
        String message = Objects.requireNonNullElse(ex.getMessage(), "").trim();
        if (message.isBlank()) {
            return ex.getClass().getSimpleName();
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("quota")) {
            return "API quota exceeded. Wait for quota reset, then retry.";
        }

        if (normalized.contains("scope") || normalized.contains("permission")) {
            return "OAuth permission missing. Reconnect account and approve required scopes.";
        }

        if (normalized.contains("access token") || normalized.contains("unauthorized") || normalized.contains("401")) {
            return "Access token expired or invalid. Reconnect account and retry.";
        }

        if (
            normalized.contains("timeout")
                || normalized.contains("timed out")
                || normalized.contains("connection")
                || normalized.contains("i/o")
                || normalized.contains("503")
                || normalized.contains("502")
                || normalized.contains("429")
        ) {
            return "Temporary network or API issue. Retry is recommended.";
        }

        String singleLine = WHITESPACE_PATTERN.matcher(message).replaceAll(" ").trim();
        if (singleLine.length() > 180) {
            return singleLine.substring(0, 177) + "...";
        }

        return singleLine;
    }

    private boolean isRetryableTrackStatus(TrackMatchStatus status) {
        return status == TrackMatchStatus.FAILED
            || status == TrackMatchStatus.PARTIAL
            || status == TrackMatchStatus.NOT_FOUND;
    }

    private String buildPlaylistTitle() {
        return "SoundBridge Migration " + Instant.now().toString();
    }

    private ScoredCandidate findBestCandidate(SpotifyTrack sourceTrack, List<YouTubeCandidate> candidates) {
        String normalizedSourceTitle = normalizeForSimilarity(sourceTrack.name());
        String normalizedSourceArtist = normalizeForSimilarity(sourceTrack.artist());
        Set<String> sourceIndicators = extractIndicators(
            Objects.requireNonNullElse(sourceTrack.name(), "") + " " + Objects.requireNonNullElse(sourceTrack.album(), "")
        );

        return candidates
            .stream()
            .map(candidate -> scoreCandidate(sourceTrack, normalizedSourceTitle, normalizedSourceArtist, sourceIndicators, candidate))
            .sorted(
                Comparator
                    .comparingDouble(ScoredCandidate::score).reversed()
                    .thenComparing(scored -> scored.candidate().videoId(), Comparator.nullsLast(String::compareTo))
                    .thenComparing(scored -> scored.candidate().title(), Comparator.nullsLast(String::compareTo))
            )
            .findFirst()
            .orElseGet(() -> new ScoredCandidate(candidates.get(0), 0.0, 0.0, 0.0, 0.0));
    }

    private ScoredCandidate scoreCandidate(
        SpotifyTrack sourceTrack,
        String normalizedSourceTitle,
        String normalizedSourceArtist,
        Set<String> sourceIndicators,
        YouTubeCandidate candidate
    ) {
        String normalizedCandidateTitle = normalizeForSimilarity(candidate.title());
        String normalizedCandidateArtist = normalizeForSimilarity(candidate.channelTitle());
        Set<String> candidateIndicators = extractIndicators(
            Objects.requireNonNullElse(candidate.title(), "") + " " + Objects.requireNonNullElse(candidate.channelTitle(), "")
        );

        double titleScore = ratioSimilarity(normalizedSourceTitle, normalizedCandidateTitle);
        double artistScore = Math.max(
            ratioSimilarity(normalizedSourceArtist, normalizedCandidateArtist),
            artistTitleCrossWeight * ratioSimilarity(normalizedSourceArtist, normalizedCandidateTitle)
        );

        double penalty = mismatchPenalty(
            normalizedSourceArtist,
            normalizedCandidateArtist,
            sourceIndicators,
            candidateIndicators
        );
        double bonus = indicatorBonus(sourceIndicators, candidateIndicators);

        double finalScore = clamp((titleWeight * titleScore) + (artistWeight * artistScore) + bonus - penalty);

        log.debug(
            "YouTube scoring | source='{}' artist='{}' | candidateId={} title='{}' channel='{}' titleScore={} artistScore={} bonus={} penalty={} finalScore={} threshold={}",
            sourceTrack.name(),
            sourceTrack.artist(),
            candidate.videoId(),
            candidate.title(),
            candidate.channelTitle(),
            round3(titleScore),
            round3(artistScore),
            round3(bonus),
            round3(penalty),
            round3(finalScore),
            round3(matchThreshold)
        );

        return new ScoredCandidate(candidate, titleScore, artistScore, penalty, finalScore);
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
        String sourceArtist,
        String candidateArtist,
        Set<String> sourceIndicators,
        Set<String> candidateIndicators
    ) {
        double penalty = 0.0;

        for (String keyword : INDICATOR_KEYWORDS) {
            boolean sourceHas = sourceIndicators.contains(keyword);
            boolean candidateHas = candidateIndicators.contains(keyword);
            if (sourceHas != candidateHas) {
                penalty += indicatorMismatchPenalty;
            }
        }

        if (!sourceArtist.isBlank() && !candidateArtist.isBlank() && ratioSimilarity(sourceArtist, candidateArtist) < 0.25) {
            penalty += 0.08;
        }

        return Math.min(0.55, penalty);
    }

    private double indicatorBonus(Set<String> sourceIndicators, Set<String> candidateIndicators) {
        if (sourceIndicators.isEmpty() || candidateIndicators.isEmpty()) {
            return 0.0;
        }

        int matches = 0;
        for (String indicator : sourceIndicators) {
            if (candidateIndicators.contains(indicator)) {
                matches += 1;
            }
        }

        return Math.min(0.12, matches * indicatorMatchBonus);
    }

    private Set<String> extractIndicators(String value) {
        Set<String> indicators = new HashSet<>();
        if (value == null || value.isBlank()) {
            return indicators;
        }

        Set<String> tokens = tokenSet(normalizeForIndicator(value));
        for (String keyword : INDICATOR_KEYWORDS) {
            if (tokens.contains(keyword)) {
                indicators.add(keyword);
            }
        }

        return indicators;
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

    private String normalizeForIndicator(String value) {
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

    private String normalizeForSimilarity(String value) {
        String normalized = normalizeForIndicator(value);
        if (normalized.isBlank()) {
            return normalized;
        }

        normalized = BRACKETED_SEGMENTS_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = FEATURING_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = NOISE_TERMS_PATTERN.matcher(normalized).replaceAll(" ");

        return normalized
            .replaceAll("\\s+", " ")
            .trim();
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

    private record ScoredCandidate(
        YouTubeCandidate candidate,
        double titleScore,
        double artistScore,
        double penalty,
        double score
    ) {
    }
}
