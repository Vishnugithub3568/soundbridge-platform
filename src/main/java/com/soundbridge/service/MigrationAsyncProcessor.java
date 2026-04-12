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
import java.util.concurrent.ThreadLocalRandom;
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
    private static final String NO_SPOTIFY_CANDIDATE_FALLBACK_REASON =
        "SAFE_FALLBACK: no Spotify candidates returned, linked Spotify search result";
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
    private static final int SPOTIFY_CANDIDATE_ATTEMPT_LIMIT = 5;
    private static final int MAX_TRACK_SLEEP_MS = 5_000;
    private static final int MAX_ADAPTIVE_COOLDOWN_MS = 20_000;

    private final MigrationJobRepository jobRepository;
    private final MigrationTrackRepository trackRepository;
    private final SpotifyClient spotifyClient;
    private final YouTubeClient youTubeClient;
    private final YouTubeMusicClient youTubeMusicClient;
    private final TrackMatchCacheService trackMatchCacheService;
    private final double matchThreshold;
    private final double retryMatchThreshold;
    private final double strictMatchThreshold;
    private final double titleWeight;
    private final double artistWeight;
    private final double indicatorMismatchPenalty;
    private final double indicatorMatchBonus;
    private final double artistTitleCrossWeight;
    private final long perTrackDelayMs;
    private final int adaptiveCooldownAfterSignals;
    private final long adaptiveCooldownMs;
    private final long checkpointCooldownMs;
    private final int checkpointCooldownEveryTracks;

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
        @Value("${youtube.match.strict.threshold:0.72}") double strictMatchThreshold,
        @Value("${youtube.match.title.weight:0.62}") double configuredTitleWeight,
        @Value("${youtube.match.artist.weight:0.38}") double configuredArtistWeight,
        @Value("${youtube.match.indicator.mismatch.penalty:0.18}") double indicatorMismatchPenalty,
        @Value("${youtube.match.indicator.match.bonus:0.04}") double indicatorMatchBonus,
        @Value("${youtube.match.artist.title.cross-weight:0.55}") double artistTitleCrossWeight,
        @Value("${migration.loop.per-track-delay-ms:150}") long perTrackDelayMs,
        @Value("${migration.loop.adaptive-cooldown-signal-threshold:3}") int adaptiveCooldownAfterSignals,
        @Value("${migration.loop.adaptive-cooldown-ms:1800}") long adaptiveCooldownMs,
        @Value("${migration.loop.checkpoint-cooldown-ms:1000}") long checkpointCooldownMs,
        @Value("${migration.loop.checkpoint-cooldown-every-tracks:25}") int checkpointCooldownEveryTracks
    ) {
        this.jobRepository = jobRepository;
        this.trackRepository = trackRepository;
        this.spotifyClient = spotifyClient;
        this.youTubeClient = youTubeClient;
        this.youTubeMusicClient = youTubeMusicClient;
        this.trackMatchCacheService = trackMatchCacheService;
        this.matchThreshold = matchThreshold;
        this.retryMatchThreshold = retryMatchThreshold;
        this.strictMatchThreshold = Math.max(matchThreshold, strictMatchThreshold);
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
        this.perTrackDelayMs = Math.max(0L, Math.min(MAX_TRACK_SLEEP_MS, perTrackDelayMs));
        this.adaptiveCooldownAfterSignals = Math.max(1, adaptiveCooldownAfterSignals);
        this.adaptiveCooldownMs = Math.max(0L, Math.min(MAX_ADAPTIVE_COOLDOWN_MS, adaptiveCooldownMs));
        this.checkpointCooldownMs = Math.max(0L, Math.min(MAX_ADAPTIVE_COOLDOWN_MS, checkpointCooldownMs));
        this.checkpointCooldownEveryTracks = Math.max(1, checkpointCooldownEveryTracks);
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
            0.72,
            0.62,
            0.38,
            0.18,
            0.04,
            0.55,
            150,
            3,
            1800,
            1000,
            25
        );
    }

    public void processMigration(UUID jobId) {
        processMigration(jobId, false);
    }

    public void processMigration(UUID jobId, boolean strictMode) {
        MigrationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("migration.stage=load-job status=missing jobId={}", jobId);
            return;
        }

        try {
            job.setStatus(JobStatus.RUNNING);
            job.setPausedReason(null);
            job.setNextRetryTime(null);
            jobRepository.saveAndFlush(job);
            log.info(
                "migration.stage=started jobId={} strictMode={} targetPlatform={} userId={}",
                jobId,
                strictMode,
                job.getTargetPlatform(),
                job.getUserId()
            );

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
            log.info(
                "migration.stage=source-tracks-loaded jobId={} strictMode={} sourceCount={} spotifyDestination={}",
                jobId,
                strictMode,
                sourceTracks.size(),
                spotifyDestination
            );

            job.setQuotaUnitsEstimated(estimateQuotaUnits(spotifyDestination, sourceTracks.size()));

            String targetPlaylistId = Objects.requireNonNullElse(job.getTargetPlaylistId(), "").trim();
            if (targetPlaylistId.isBlank()) {
                targetPlaylistId = createTargetPlaylist(job, spotifyDestination, targetAccessToken);
                job.setTargetPlaylistId(targetPlaylistId);
                job.setTargetPlaylistUrl(buildTargetPlaylistUrl(spotifyDestination, targetPlaylistId));
                log.info(
                    "migration.stage=target-playlist-created jobId={} playlistId={} targetPlatform={}",
                    jobId,
                    targetPlaylistId,
                    job.getTargetPlatform()
                );
            } else {
                log.info(
                    "migration.stage=target-playlist-reused jobId={} playlistId={} targetPlatform={}",
                    jobId,
                    targetPlaylistId,
                    job.getTargetPlatform()
                );
            }

            job.setTotalTracks(sourceTracks.size());
            int startIndex = Math.max(0, Math.min(job.getLastProcessedIndex(), sourceTracks.size()));

            if (startIndex == 0) {
                job.setMatchedTracks(0);
                job.setFailedTracks(0);
            }
            jobRepository.saveAndFlush(job);

            int rateLimitSignals = 0;
            for (int batchStart = startIndex; batchStart < sourceTracks.size(); batchStart += BATCH_SIZE) {
                int batchEnd = Math.min(sourceTracks.size(), batchStart + BATCH_SIZE);
                log.info(
                    "migration.stage=batch-processing jobId={} strictMode={} batchStart={} batchEnd={} totalTracks={}",
                    jobId,
                    strictMode,
                    batchStart,
                    batchEnd,
                    sourceTracks.size()
                );

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

                    double effectiveMatchThreshold = strictMode ? strictMatchThreshold : matchThreshold;

                    boolean matchedTrack = spotifyDestination
                        ? processSpotifyDestinationTrack(migrationTrack, sourceTrack, targetPlaylistId, targetAccessToken)
                        : processYouTubeDestinationTrack(
                            job,
                            migrationTrack,
                            sourceTrack,
                            targetPlaylistId,
                            targetAccessToken,
                            strictMode,
                            effectiveMatchThreshold
                        );

                    if (matchedTrack) {
                        job.setMatchedTracks(job.getMatchedTracks() + 1);
                    } else {
                        job.setFailedTracks(job.getFailedTracks() + 1);
                    }

                    trackRepository.save(migrationTrack);
                    job.setLastProcessedIndex(trackIndex + 1);
                    jobRepository.saveAndFlush(job);

                    if (isRateLimitSignal(migrationTrack)) {
                        rateLimitSignals++;
                    } else {
                        rateLimitSignals = 0;
                    }

                    throttleLoop(jobId, trackIndex - startIndex + 1, rateLimitSignals);
                }
            }

            job.setStatus(JobStatus.COMPLETED);
            job.setPausedReason(null);
            job.setNextRetryTime(null);
            jobRepository.saveAndFlush(job);
            log.info(
                "migration.stage=completed jobId={} strictMode={} matchedTracks={} failedTracks={} totalTracks={}",
                jobId,
                strictMode,
                job.getMatchedTracks(),
                job.getFailedTracks(),
                job.getTotalTracks()
            );
        } catch (QuotaExceededException ex) {
            job.setStatus(JobStatus.QUOTA_PAUSED);
            job.setPausedReason(QUOTA_PAUSED_REASON);
            job.setNextRetryTime(Instant.now().plusSeconds(24L * 60L * 60L));
            jobRepository.saveAndFlush(job);
            log.warn(
                "migration.stage=paused-quota jobId={} strictMode={} matchedTracks={} failedTracks={} nextRetryTime={}",
                jobId,
                strictMode,
                job.getMatchedTracks(),
                job.getFailedTracks(),
                job.getNextRetryTime()
            );
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
            log.error(
                "migration.stage=failed jobId={} strictMode={} matchedTracks={} failedTracks={} totalTracks={} reason={}",
                jobId,
                strictMode,
                job.getMatchedTracks(),
                job.getFailedTracks(),
                job.getTotalTracks(),
                summarizeError(ex),
                ex
            );
        }
    }

    public void retryFailedTracks(UUID jobId) {
        MigrationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("migration.stage=retry-load-job status=missing jobId={}", jobId);
            return;
        }

        try {
            job.setStatus(JobStatus.RUNNING);
            job.setPausedReason(null);
            job.setNextRetryTime(null);
            jobRepository.saveAndFlush(job);
            log.info(
                "migration.stage=retry-started jobId={} targetPlatform={} userId={}",
                jobId,
                job.getTargetPlatform(),
                job.getUserId()
            );

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
                log.info(
                    "migration.stage=retry-target-playlist-created jobId={} playlistId={} targetPlatform={}",
                    jobId,
                    targetPlaylistId,
                    job.getTargetPlatform()
                );
            }

            List<MigrationTrack> allTracks = new ArrayList<>(trackRepository.findByJobIdOrderByIdAsc(jobId));
            int processedRetryTracks = 0;
            int rateLimitSignals = 0;
            for (MigrationTrack track : allTracks) {
                if (!isRetryableTrackStatus(track.getMatchStatus())) {
                    continue;
                }
                processedRetryTracks++;

                SpotifyTrack sourceTrack = new SpotifyTrack(
                    Objects.requireNonNullElse(track.getSourceTrackName(), ""),
                    Objects.requireNonNullElse(track.getSourceArtistName(), ""),
                    track.getSourceAlbumName(),
                    null
                );

                boolean matchedTrack = spotifyDestination
                    ? processSpotifyDestinationTrack(track, sourceTrack, targetPlaylistId, targetAccessToken)
                    : processYouTubeDestinationTrack(
                        job,
                        track,
                        sourceTrack,
                        targetPlaylistId,
                        targetAccessToken,
                        false,
                        retryMatchThreshold
                    );

                if (matchedTrack && track.getMatchStatus() == TrackMatchStatus.MATCHED) {
                    track.setFailureReason(null);
                }

                trackRepository.save(track);

                if (isRateLimitSignal(track)) {
                    rateLimitSignals++;
                } else {
                    rateLimitSignals = 0;
                }

                throttleLoop(jobId, processedRetryTracks, rateLimitSignals);
            }

            refreshJobCounters(job);
            job.setStatus(JobStatus.COMPLETED);
            jobRepository.saveAndFlush(job);
            log.info(
                "migration.stage=retry-completed jobId={} matchedTracks={} failedTracks={} totalTracks={}",
                jobId,
                job.getMatchedTracks(),
                job.getFailedTracks(),
                job.getTotalTracks()
            );
        } catch (QuotaExceededException ex) {
            refreshJobCounters(job);
            job.setStatus(JobStatus.QUOTA_PAUSED);
            job.setPausedReason(QUOTA_PAUSED_REASON);
            job.setNextRetryTime(Instant.now().plusSeconds(24L * 60L * 60L));
            jobRepository.saveAndFlush(job);
            log.warn(
                "migration.stage=retry-paused-quota jobId={} matchedTracks={} failedTracks={} nextRetryTime={}",
                jobId,
                job.getMatchedTracks(),
                job.getFailedTracks(),
                job.getNextRetryTime()
            );
        } catch (Exception ex) {
            refreshJobCounters(job);
            job.setStatus(JobStatus.FAILED);
            jobRepository.saveAndFlush(job);
            log.error(
                "migration.stage=retry-failed jobId={} matchedTracks={} failedTracks={} totalTracks={} reason={}",
                jobId,
                job.getMatchedTracks(),
                job.getFailedTracks(),
                job.getTotalTracks(),
                summarizeError(ex),
                ex
            );
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
        double threshold,
        boolean strictMode
    ) {
        if (candidates == null || candidates.isEmpty()) {
            if (strictMode) {
                markTrackAsNotFound(
                    migrationTrack,
                    "FAILED: Strict mode rejected fallback because no confident candidate was found"
                );
                return false;
            }
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

        if (strictMode) {
            markTrackAsNotFound(
                migrationTrack,
                "FAILED: Strict mode rejected low-confidence candidate (score=" + round3(best.score()) + ")"
            );
            return false;
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

    private void markTrackAsNotFound(MigrationTrack migrationTrack, String reason) {
        migrationTrack.setMatchStatus(TrackMatchStatus.NOT_FOUND);
        migrationTrack.setConfidenceScore(0.0);
        migrationTrack.setMatchScore(0.0);
        migrationTrack.setFailureReason(reason);
        migrationTrack.setTargetTrackId(null);
        migrationTrack.setTargetTrackUrl(null);
        migrationTrack.setTargetTrackTitle(null);
        migrationTrack.setTargetThumbnailUrl(null);
        migrationTrack.setYouTubeTitle(null);
        migrationTrack.setYouTubeVideoId(null);
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
        String googleAccessToken,
        boolean strictMode,
        double threshold
    ) {
        try {
            String videoId = trackMatchCacheService.findCachedVideoId(job.getUserId(), sourceTrack).orElse("").trim();
            if (videoId.isBlank()) {
                List<YouTubeCandidate> candidates = youTubeClient.searchCandidates(sourceTrack);
                boolean matchedTrack = applyCandidateMatch(migrationTrack, sourceTrack, candidates, threshold, strictMode);
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

            if (strictMode) {
                markTrackAsNotFound(
                    migrationTrack,
                    "FAILED: Strict mode disabled lookup fallback after provider error: " + summarizeError(ex)
                );
                return false;
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
                applySpotifySearchFallbackMatch(
                    migrationTrack,
                    sourceTrack,
                    NO_SPOTIFY_CANDIDATE_FALLBACK_REASON
                );
                log.warn(
                    "Spotify destination lookup returned no candidates for source='{}' artist='{}'; using Spotify search fallback",
                    sourceTrack.name(),
                    sourceTrack.artist()
                );
                return true;
            }

            RuntimeException addFailure = tryAddCandidateToSpotifyPlaylist(
                migrationTrack,
                sourceTrack,
                targetPlaylistId,
                spotifyAccessToken,
                candidates
            );
            if (addFailure != null) {
                String message = "SAFE_FALLBACK: Spotify candidates found but all playlist add attempts failed: "
                    + summarizeError(addFailure);
                applySpotifySearchFallbackMatch(migrationTrack, sourceTrack, message);
                log.warn(
                    "Spotify destination add failed for all candidates source='{}' artist='{}'; using fallback",
                    sourceTrack.name(),
                    sourceTrack.artist()
                );
            }
            return true;
        } catch (RuntimeException ex) {
            applySpotifySearchFallbackMatch(
                migrationTrack,
                sourceTrack,
                "SAFE_FALLBACK: Spotify lookup unavailable, linked Spotify search result"
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

    private RuntimeException tryAddCandidateToSpotifyPlaylist(
        MigrationTrack migrationTrack,
        SpotifyTrack sourceTrack,
        String targetPlaylistId,
        String spotifyAccessToken,
        List<SpotifyClient.SpotifySearchCandidate> candidates
    ) {
        RuntimeException lastAddError = null;
        int attempts = Math.min(SPOTIFY_CANDIDATE_ATTEMPT_LIMIT, candidates.size());

        for (int index = 0; index < attempts; index++) {
            SpotifyClient.SpotifySearchCandidate candidate = candidates.get(index);
            if (candidate == null || candidate.id() == null || candidate.id().isBlank()) {
                continue;
            }

            try {
                String candidateUri = buildSpotifyTrackUri(candidate);
                if (candidateUri.isBlank()) {
                    continue;
                }

                spotifyClient.addTrackToPlaylist(spotifyAccessToken, targetPlaylistId, candidateUri);

                migrationTrack.setMatchStatus(TrackMatchStatus.MATCHED);
                migrationTrack.setConfidenceScore(1.0);
                migrationTrack.setMatchScore(1.0);
                migrationTrack.setTargetTrackId(candidate.id());
                migrationTrack.setTargetTrackUrl(candidate.externalUrl().isBlank()
                    ? "https://open.spotify.com/track/" + candidate.id()
                    : candidate.externalUrl());
                migrationTrack.setTargetTrackTitle(candidate.name());
                migrationTrack.setTargetThumbnailUrl(candidate.thumbnailUrl());
                migrationTrack.setYouTubeTitle(null);
                migrationTrack.setYouTubeVideoId(null);
                migrationTrack.setFailureReason(null);

                if (index > 0) {
                    log.info(
                        "Spotify destination recovered by alternate candidate source='{}' artist='{}' chosenCandidateIndex={} candidateId={}",
                        sourceTrack.name(),
                        sourceTrack.artist(),
                        index,
                        candidate.id()
                    );
                }

                return null;
            } catch (RuntimeException addError) {
                lastAddError = addError;
                log.debug(
                    "Spotify add attempt failed source='{}' artist='{}' candidateIndex={} candidateId={} reason={}",
                    sourceTrack.name(),
                    sourceTrack.artist(),
                    index,
                    candidate.id(),
                    summarizeError(addError)
                );
            }
        }

        if (lastAddError != null) {
            log.warn(
                "Spotify destination candidate attempts exhausted source='{}' artist='{}' reason={}",
                sourceTrack.name(),
                sourceTrack.artist(),
                summarizeError(lastAddError)
            );
        }

        return lastAddError == null
            ? new IllegalStateException("No valid Spotify candidate URI was available for playlist insertion")
            : lastAddError;
    }

    private String buildSpotifyTrackUri(SpotifyClient.SpotifySearchCandidate candidate) {
        String uri = Objects.requireNonNullElse(candidate.uri(), "").trim();
        if (!uri.isBlank()) {
            return uri;
        }

        String id = Objects.requireNonNullElse(candidate.id(), "").trim();
        if (id.isBlank()) {
            return "";
        }

        return "spotify:track:" + id;
    }

    private void applySpotifySearchFallbackMatch(MigrationTrack migrationTrack, SpotifyTrack sourceTrack, String reason) {
        String searchQuery = (Objects.requireNonNullElse(sourceTrack.name(), "") + " "
            + Objects.requireNonNullElse(sourceTrack.artist(), "")).trim();
        String encoded = URLEncoder.encode(searchQuery, StandardCharsets.UTF_8);

        migrationTrack.setMatchStatus(TrackMatchStatus.PARTIAL);
        migrationTrack.setConfidenceScore(0.0);
        migrationTrack.setMatchScore(0.0);
        migrationTrack.setTargetTrackId("search:" + encoded);
        migrationTrack.setTargetTrackUrl("https://open.spotify.com/search/" + encoded);
        migrationTrack.setTargetTrackTitle(searchQuery.isBlank() ? "Spotify Search" : searchQuery + " (Search)");
        migrationTrack.setTargetThumbnailUrl(null);
        migrationTrack.setYouTubeTitle(null);
        migrationTrack.setYouTubeVideoId(null);
        migrationTrack.setFailureReason(reason);
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

        if (normalized.contains("active premium subscription required for the owner of the app")) {
            return "Spotify rejected playlist creation because the Spotify app owner account does not meet required subscription status. Use an app owned by an eligible account or update the owner subscription, then reconnect Spotify.";
        }

        if (normalized.contains("access token") || normalized.contains("unauthorized") || normalized.contains("401")) {
            return "Access token expired or invalid. Reconnect account and retry.";
        }

        if (
            normalized.contains("non-json content")
                || normalized.contains("httpmessageconverter")
                || normalized.contains("text/html")
        ) {
            if (normalized.contains("youtube")) {
                return "YouTube source API returned an unexpected web page instead of API data. Reconnect Google and verify backend YOUTUBE_MUSIC_API_BASE_URL is https://www.googleapis.com/youtube/v3.";
            }
            return "Spotify returned an unexpected web page instead of API data. Reconnect Spotify and verify backend SPOTIFY_API_BASE_URL is https://api.spotify.com/v1.";
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

    private void throttleLoop(UUID jobId, int processedTracks, int rateLimitSignals) {
        sleepWithInterruptHandling(perTrackDelayMs, "Migration processing interrupted");

        if (rateLimitSignals >= adaptiveCooldownAfterSignals && adaptiveCooldownMs > 0L) {
            long jitterMs = ThreadLocalRandom.current().nextLong(0L, 250L);
            long delayMs = Math.min(MAX_ADAPTIVE_COOLDOWN_MS, adaptiveCooldownMs + jitterMs);
            log.warn(
                "migration.stage=adaptive-cooldown jobId={} processedTracks={} consecutiveRateLimitSignals={} cooldownMs={}",
                jobId,
                processedTracks,
                rateLimitSignals,
                delayMs
            );
            sleepWithInterruptHandling(delayMs, "Migration processing interrupted during adaptive cooldown");
            return;
        }

        if (checkpointCooldownMs > 0L && processedTracks % checkpointCooldownEveryTracks == 0) {
            log.info(
                "migration.stage=checkpoint-cooldown jobId={} processedTracks={} cooldownMs={}",
                jobId,
                processedTracks,
                checkpointCooldownMs
            );
            sleepWithInterruptHandling(checkpointCooldownMs, "Migration processing interrupted during checkpoint cooldown");
        }
    }

    private void sleepWithInterruptHandling(long delayMs, String interruptMessage) {
        if (delayMs <= 0L) {
            return;
        }

        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interruptMessage, interruptedException);
        }
    }

    private boolean isRateLimitSignal(MigrationTrack track) {
        if (track == null) {
            return false;
        }

        String reason = Objects.requireNonNullElse(track.getFailureReason(), "").toLowerCase(Locale.ROOT);
        return reason.contains("429")
            || reason.contains("rate limit")
            || reason.contains("quota")
            || reason.contains("temporary network or api issue");
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
