package com.soundbridge.service;

import com.soundbridge.dto.CreateMigrationRequest;
import com.soundbridge.dto.IssueCategoryClassifier;
import com.soundbridge.dto.MigrationJobResponse;
import com.soundbridge.dto.MigrationQuotaEstimateResponse;
import com.soundbridge.dto.MigrationPreflightRequest;
import com.soundbridge.dto.MigrationPreflightResponse;
import com.soundbridge.dto.MigrationReportResponse;
import com.soundbridge.dto.MigrationTrackResponse;
import com.soundbridge.client.SpotifyClient;
import com.soundbridge.client.YouTubeMusicClient;
import com.soundbridge.exception.MigrationException;
import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import com.soundbridge.model.TrackMatchStatus;
import com.soundbridge.repository.MigrationJobRepository;
import com.soundbridge.repository.MigrationTrackRepository;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressWarnings("null")
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private static final Pattern SPOTIFY_PLAYLIST_PATTERN = Pattern.compile("open\\.spotify\\.com/playlist/([A-Za-z0-9]+)");
    private static final int QUOTA_WARNING_THRESHOLD_UNITS = 8000;
    private static final int YOUTUBE_PLAYLIST_CREATE_UNITS = 50;
    private static final int SPOTIFY_TO_YOUTUBE_PER_TRACK_UNITS = 101;
    private static final int YOUTUBE_TO_SPOTIFY_PER_TRACK_UNITS = 2;

    private final MigrationJobRepository jobRepository;
    private final MigrationTrackRepository trackRepository;
    private final MigrationAsyncProcessor migrationAsyncProcessor;
    private final ApplicationContext applicationContext;
    private final GoogleOAuthService googleOAuthService;
    private final SpotifyClient spotifyClient;
    private final YouTubeMusicClient youTubeMusicClient;

    public MigrationService(
        MigrationJobRepository jobRepository,
        MigrationTrackRepository trackRepository,
        MigrationAsyncProcessor migrationAsyncProcessor,
        ApplicationContext applicationContext,
        GoogleOAuthService googleOAuthService,
        SpotifyClient spotifyClient,
        YouTubeMusicClient youTubeMusicClient
    ) {
        this.jobRepository = jobRepository;
        this.trackRepository = trackRepository;
        this.migrationAsyncProcessor = migrationAsyncProcessor;
        this.applicationContext = applicationContext;
        this.googleOAuthService = googleOAuthService;
        this.spotifyClient = spotifyClient;
        this.youTubeMusicClient = youTubeMusicClient;
    }

    public MigrationJobResponse startMigration(CreateMigrationRequest request) {
        if (request == null) {
            throw new MigrationException("Migration request is required", "MISSING_REQUEST", 400);
        }

        if (request.isSpotifyToYouTube()) {
            return startSpotifyToYouTubeMigration(request);
        } else if (request.isYouTubeToSpotify()) {
            return startYouTubeToSpotifyMigration(request);
        } else {
            throw new MigrationException("Invalid migration direction", "INVALID_DIRECTION", 400);
        }
    }

    public MigrationPreflightResponse preflight(MigrationPreflightRequest request) {
        if (request == null) {
            throw new MigrationException("Migration request is required", "MISSING_REQUEST", 400);
        }

        String direction = Objects.requireNonNullElse(request.getDirection(), "SPOTIFY_TO_YOUTUBE").trim();
        String sourcePlaylistUrl = Objects.requireNonNullElse(request.getSourcePlaylistUrl(), "").trim();
        String spotifyAccessToken = Objects.requireNonNullElse(request.getSpotifyAccessToken(), "").trim();
        String googleAccessToken = Objects.requireNonNullElse(request.getGoogleAccessToken(), "").trim();

        List<String> blockers = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        String sourcePlatform;
        String targetPlatform;
        String playlistId = "";
        boolean validUrl = false;

        if ("SPOTIFY_TO_YOUTUBE".equalsIgnoreCase(direction)) {
            sourcePlatform = "SPOTIFY";
            targetPlatform = "YOUTUBE_MUSIC";

            playlistId = extractSpotifyPlaylistIdSafe(sourcePlaylistUrl);
            validUrl = !playlistId.isBlank();
            if (!validUrl) {
                blockers.add("Invalid Spotify playlist URL. Use https://open.spotify.com/playlist/{id}");
            }

            if (googleAccessToken.isBlank()) {
                blockers.add("Google login is required to create playlists in YouTube Music.");
            } else if (!googleOAuthService.hasYouTubeWriteScope(googleAccessToken)) {
                blockers.add("Google token is missing YouTube write permission.");
                recommendations.add("Reconnect Google and approve YouTube access scope.");
            }

            if (spotifyAccessToken.isBlank()) {
                recommendations.add("Connect Spotify if the source playlist is private or collaborative.");
            }
        } else if ("YOUTUBE_TO_SPOTIFY".equalsIgnoreCase(direction)) {
            sourcePlatform = "YOUTUBE_MUSIC";
            targetPlatform = "SPOTIFY";

            try {
                playlistId = youTubeMusicClient.extractPlaylistId(sourcePlaylistUrl);
                validUrl = !playlistId.isBlank();
            } catch (IllegalArgumentException ex) {
                validUrl = false;
            }

            if (!validUrl) {
                blockers.add("Invalid YouTube Music playlist URL. Use https://music.youtube.com/playlist?list={id}");
            }

            if (googleAccessToken.isBlank()) {
                blockers.add("Google login is required to read YouTube Music playlists.");
            }

            if (spotifyAccessToken.isBlank()) {
                blockers.add("Spotify login is required to create playlists and add tracks.");
            }
        } else {
            sourcePlatform = "UNKNOWN";
            targetPlatform = "UNKNOWN";
            blockers.add("Invalid migration direction selected.");
        }

        boolean readyToStart = validUrl && blockers.isEmpty();
        MigrationQuotaEstimateResponse quotaEstimate = estimateQuotaInternal(
            direction,
            sourcePlaylistUrl,
            spotifyAccessToken,
            googleAccessToken
        );
        if (quotaEstimate.warning()) {
            recommendations.add(quotaEstimate.warningMessage());
        }

        return new MigrationPreflightResponse(
            validUrl,
            readyToStart,
            direction,
            sourcePlatform,
            targetPlatform,
            playlistId,
            blockers,
            recommendations,
            quotaEstimate.totalTracks(),
            quotaEstimate.estimatedQuotaUnits(),
            quotaEstimate.warning(),
            quotaEstimate.warningMessage()
        );
    }

    public MigrationQuotaEstimateResponse estimateQuota(
        String direction,
        String sourcePlaylistUrl,
        String spotifyAccessToken,
        String googleAccessToken
    ) {
        return estimateQuotaInternal(direction, sourcePlaylistUrl, spotifyAccessToken, googleAccessToken);
    }

    private MigrationJobResponse startSpotifyToYouTubeMigration(CreateMigrationRequest request) {
        String spotifyPlaylistUrl = Objects.requireNonNullElse(request.getSourcePlaylistUrl(), "").trim();
        if (spotifyPlaylistUrl.isEmpty()) {
            throw new MigrationException("Spotify playlist URL is required", "MISSING_PLAYLIST_URL", 400);
        }

        String spotifyAccessToken = Objects.requireNonNullElse(request.getSpotifyAccessToken(), "").trim();
        String googleAccessToken = Objects.requireNonNullElse(request.getGoogleAccessToken(), "").trim();
        boolean strictMode = request.isStrictMode();

        if (!googleAccessToken.isEmpty() && !googleOAuthService.hasYouTubeWriteScope(googleAccessToken)) {
            throw new MigrationException(
                "Google token is missing YouTube permission. Reconnect Google and approve YouTube access, then retry.",
                "MISSING_YOUTUBE_SCOPE",
                400
            );
        }

        MigrationJob job = new MigrationJob();
        job.setSourcePlaylistUrl(spotifyPlaylistUrl);
        job.setSpotifyAccessToken(spotifyAccessToken.isEmpty() ? null : spotifyAccessToken);
        job.setGoogleAccessToken(googleAccessToken.isEmpty() ? null : googleAccessToken);
        job.setUserId(request.getUserId());
        job.setTargetPlatform("YOUTUBE_MUSIC");
        job.setStatus(JobStatus.QUEUED);
        job.setTotalTracks(0);
        job.setMatchedTracks(0);
        job.setFailedTracks(0);
        job.setLastProcessedIndex(0);
        job.setPausedReason(null);
        job.setQuotaUnitsEstimated(null);
        job.setNextRetryTime(null);
        job = jobRepository.saveAndFlush(job);

        UUID jobId = job.getId();
        log.info(
            "migration.service=queued jobId={} direction=SPOTIFY_TO_YOUTUBE strictMode={} userId={} sourcePlaylistUrl={}",
            jobId,
            strictMode,
            request.getUserId(),
            spotifyPlaylistUrl
        );
        applicationContext.getBean(MigrationService.class).processMigrationAsync(jobId, strictMode);
        return MigrationJobResponse.from(job);
    }

    private MigrationJobResponse startYouTubeToSpotifyMigration(CreateMigrationRequest request) {
        String youtubePlaylistUrl = Objects.requireNonNullElse(request.getSourcePlaylistUrl(), "").trim();
        if (youtubePlaylistUrl.isEmpty()) {
            throw new MigrationException("YouTube Music playlist URL is required", "MISSING_PLAYLIST_URL", 400);
        }

        try {
            youTubeMusicClient.extractPlaylistId(youtubePlaylistUrl);
        } catch (IllegalArgumentException ex) {
            throw new MigrationException("Invalid YouTube Music playlist URL", "INVALID_PLAYLIST_URL", 400, ex);
        }

        String googleAccessToken = Objects.requireNonNullElse(request.getGoogleAccessToken(), "").trim();
        String spotifyAccessToken = Objects.requireNonNullElse(request.getSpotifyAccessToken(), "").trim();
        boolean strictMode = request.isStrictMode();

        if (googleAccessToken.isEmpty()) {
            throw new MigrationException("Google access token is required for YouTube Music access", "MISSING_GOOGLE_TOKEN", 400);
        }

        if (spotifyAccessToken.isEmpty()) {
            throw new MigrationException("Spotify access token is required to create playlists", "MISSING_SPOTIFY_TOKEN", 400);
        }

        MigrationJob job = new MigrationJob();
        job.setSourcePlaylistUrl(youtubePlaylistUrl);
        job.setSpotifyAccessToken(spotifyAccessToken);
        job.setGoogleAccessToken(googleAccessToken);
        job.setUserId(request.getUserId());
        job.setTargetPlatform("SPOTIFY");
        job.setStatus(JobStatus.QUEUED);
        job.setTotalTracks(0);
        job.setMatchedTracks(0);
        job.setFailedTracks(0);
        job.setLastProcessedIndex(0);
        job.setPausedReason(null);
        job.setQuotaUnitsEstimated(null);
        job.setNextRetryTime(null);
        job = jobRepository.saveAndFlush(job);

        UUID jobId = job.getId();
        log.info(
            "migration.service=queued jobId={} direction=YOUTUBE_TO_SPOTIFY strictMode={} userId={} sourcePlaylistUrl={}",
            jobId,
            strictMode,
            request.getUserId(),
            youtubePlaylistUrl
        );
        applicationContext.getBean(MigrationService.class).processMigrationAsync(jobId, strictMode);
        return MigrationJobResponse.from(job);
    }

    public MigrationJobResponse startMigration(String spotifyPlaylistUrl, String spotifyAccessToken, String googleAccessToken) {
        String normalizedUrl = Objects.requireNonNullElse(spotifyPlaylistUrl, "").trim();
        if (normalizedUrl.isEmpty()) {
            throw new MigrationException("Spotify playlist URL is required", "MISSING_PLAYLIST_URL", 400);
        }

        String normalizedToken = Objects.requireNonNullElse(spotifyAccessToken, "").trim();
        String normalizedGoogleToken = Objects.requireNonNullElse(googleAccessToken, "").trim();

        if (!normalizedGoogleToken.isEmpty() && !googleOAuthService.hasYouTubeWriteScope(normalizedGoogleToken)) {
            throw new MigrationException(
                "Google token is missing YouTube permission. Reconnect Google and approve YouTube access, then retry.",
                "MISSING_YOUTUBE_SCOPE",
                400
            );
        }

        MigrationJob job = new MigrationJob();
        job.setSourcePlaylistUrl(normalizedUrl);
        job.setSpotifyAccessToken(normalizedToken.isEmpty() ? null : normalizedToken);
        job.setGoogleAccessToken(normalizedGoogleToken.isEmpty() ? null : normalizedGoogleToken);
        job.setTargetPlatform("YOUTUBE_MUSIC");
        job.setStatus(JobStatus.QUEUED);
        job.setTotalTracks(0);
        job.setMatchedTracks(0);
        job.setFailedTracks(0);
        job.setLastProcessedIndex(0);
        job.setPausedReason(null);
        job.setQuotaUnitsEstimated(null);
        job.setNextRetryTime(null);
        job = jobRepository.saveAndFlush(job);

        UUID jobId = job.getId();
        log.info(
            "migration.service=queued jobId={} direction=SPOTIFY_TO_YOUTUBE strictMode=false userId={} sourcePlaylistUrl={}",
            jobId,
            null,
            normalizedUrl
        );
        applicationContext.getBean(MigrationService.class).processMigrationAsync(jobId, false);
        return MigrationJobResponse.from(job);
    }

    public MigrationJobResponse retryFailedTracks(UUID jobId) {
        MigrationJob job = getJobEntity(jobId);
        if (job.getStatus() == JobStatus.RUNNING || job.getStatus() == JobStatus.QUEUED) {
            throw new MigrationException("Migration job is already in progress", "JOB_IN_PROGRESS", 409);
        }

        boolean hasRetryableTracks = trackRepository.findByJobIdOrderByIdAsc(jobId)
            .stream()
            .anyMatch(track -> {
                TrackMatchStatus status = track.getMatchStatus();
                return status == TrackMatchStatus.FAILED
                    || status == TrackMatchStatus.PARTIAL
                    || status == TrackMatchStatus.NOT_FOUND;
            });

        if (!hasRetryableTracks) {
            return MigrationJobResponse.from(job);
        }

        job.setStatus(JobStatus.QUEUED);
        jobRepository.saveAndFlush(job);
        log.info("migration.service=retry-queued jobId={} status={}", jobId, job.getStatus());
        applicationContext.getBean(MigrationService.class).retryFailedTracksAsync(jobId);
        return MigrationJobResponse.from(job);
    }

    public MigrationJobResponse resumeJob(UUID jobId) {
        MigrationJob job = getJobEntity(jobId);
        if (job.getStatus() == JobStatus.RUNNING || job.getStatus() == JobStatus.QUEUED) {
            throw new MigrationException("Migration job is already in progress", "JOB_IN_PROGRESS", 409);
        }

        if (job.getStatus() != JobStatus.QUOTA_PAUSED) {
            throw new MigrationException("Only quota-paused jobs can be resumed", "JOB_NOT_QUOTA_PAUSED", 409);
        }

        job.setStatus(JobStatus.QUEUED);
        job.setPausedReason(null);
        job.setNextRetryTime(null);
        jobRepository.saveAndFlush(job);
        log.info("migration.service=resume-queued jobId={} status={}", jobId, job.getStatus());
        applicationContext.getBean(MigrationService.class).processMigrationAsync(jobId, false);
        return MigrationJobResponse.from(job);
    }

    @Transactional(readOnly = true)
    public List<MigrationJobResponse> getJobHistory(UUID userId, int limit) {
        int normalizedLimit = Math.max(1, Math.min(100, limit));
        return jobRepository.findByUserIdOrderByUpdatedAtDesc(userId, PageRequest.of(0, normalizedLimit))
            .stream()
            .map(MigrationJobResponse::from)
            .toList();
    }

    @Async("migrationTaskExecutor")
    public void processMigrationAsync(UUID jobId) {
        processMigrationAsync(jobId, false);
    }

    @Async("migrationTaskExecutor")
    public void processMigrationAsync(UUID jobId, boolean strictMode) {
        log.info("migration.service=dispatch-process jobId={} strictMode={}", jobId, strictMode);
        migrationAsyncProcessor.processMigration(jobId, strictMode);
    }

    @Async("migrationTaskExecutor")
    public void retryFailedTracksAsync(UUID jobId) {
        log.info("migration.service=dispatch-retry jobId={}", jobId);
        migrationAsyncProcessor.retryFailedTracks(jobId);
    }

    @Transactional(readOnly = true)
    public MigrationJobResponse getJob(UUID jobId) {
        return MigrationJobResponse.from(getJobEntity(jobId));
    }

    @Transactional(readOnly = true)
    public List<MigrationTrackResponse> getTracks(UUID jobId) {
        ensureJobExists(jobId);
        return trackRepository.findByJobIdOrderByIdAsc(jobId)
            .stream()
            .map(MigrationTrackResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public MigrationReportResponse getReport(UUID jobId) {
        MigrationJob job = getJobEntity(jobId);
        List<MigrationTrackResponse> tracks = getTracks(jobId);
        double matchRate = job.getTotalTracks() == 0
            ? 0.0
            : ((double) job.getMatchedTracks() / (double) job.getTotalTracks()) * 100.0;

        Map<String, Integer> issueCategoryCounts = new LinkedHashMap<>();
        for (MigrationTrackResponse track : tracks) {
            String category = String.valueOf(track.issueCategory());
            if (category.isBlank() || "NONE".equalsIgnoreCase(category)) {
                continue;
            }
            issueCategoryCounts.merge(category, 1, Integer::sum);
        }

        String dominantIssueCategory = issueCategoryCounts.entrySet()
            .stream()
            .max(Comparator.comparingInt(Map.Entry::getValue))
            .map(Map.Entry::getKey)
            .orElse("NONE");

        String dominantIssueAction = IssueCategoryClassifier.recommendedAction(dominantIssueCategory);

        return new MigrationReportResponse(
            job.getId(),
            job.getStatus(),
            job.getTotalTracks(),
            job.getMatchedTracks(),
            job.getFailedTracks(),
            matchRate,
            tracks,
            issueCategoryCounts,
            dominantIssueCategory,
            dominantIssueAction
        );
    }

    private MigrationJob getJobEntity(UUID jobId) {
        return jobRepository.findById(jobId)
            .orElseThrow(() -> new MigrationException("Migration job not found", "JOB_NOT_FOUND", 404));
    }

    private String extractSpotifyPlaylistIdSafe(String playlistUrl) {
        String normalized = Objects.requireNonNullElse(playlistUrl, "").trim();
        if (normalized.isEmpty()) {
            return "";
        }

        Matcher matcher = SPOTIFY_PLAYLIST_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return "";
    }

    private MigrationQuotaEstimateResponse estimateQuotaInternal(
        String direction,
        String sourcePlaylistUrl,
        String spotifyAccessToken,
        String googleAccessToken
    ) {
        String normalizedDirection = Objects.requireNonNullElse(direction, "SPOTIFY_TO_YOUTUBE").trim();
        String normalizedUrl = Objects.requireNonNullElse(sourcePlaylistUrl, "").trim();
        String normalizedSpotifyToken = Objects.requireNonNullElse(spotifyAccessToken, "").trim();
        String normalizedGoogleToken = Objects.requireNonNullElse(googleAccessToken, "").trim();

        int totalTracks = 0;
        int estimatedUnits = 0;
        String warningMessage = "";

        try {
            if ("SPOTIFY_TO_YOUTUBE".equalsIgnoreCase(normalizedDirection) && !normalizedUrl.isBlank()) {
                totalTracks = spotifyClient.fetchPlaylistTracks(
                    normalizedUrl,
                    normalizedSpotifyToken.isBlank() ? null : normalizedSpotifyToken
                ).size();
                estimatedUnits = YOUTUBE_PLAYLIST_CREATE_UNITS + (totalTracks * SPOTIFY_TO_YOUTUBE_PER_TRACK_UNITS);
            } else if ("YOUTUBE_TO_SPOTIFY".equalsIgnoreCase(normalizedDirection) && !normalizedUrl.isBlank()) {
                if (normalizedGoogleToken.isBlank()) {
                    warningMessage = "Google login is required to estimate source playlist size for YouTube migrations.";
                } else {
                    totalTracks = youTubeMusicClient.fetchPlaylistTracks(normalizedUrl, normalizedGoogleToken).size();
                    estimatedUnits = totalTracks * YOUTUBE_TO_SPOTIFY_PER_TRACK_UNITS;
                }
            }
        } catch (RuntimeException ex) {
            warningMessage = "Unable to estimate quota right now: " + ex.getMessage();
        }

        boolean warning = estimatedUnits >= QUOTA_WARNING_THRESHOLD_UNITS || !warningMessage.isBlank();
        if (warningMessage.isBlank() && estimatedUnits >= QUOTA_WARNING_THRESHOLD_UNITS) {
            warningMessage = "This playlist is large and may hit YouTube daily quota limits."
                + " Progress can be paused and resumed automatically.";
        }

        return new MigrationQuotaEstimateResponse(
            normalizedDirection,
            Math.max(0, totalTracks),
            Math.max(0, estimatedUnits),
            warning,
            warningMessage
        );
    }

    private void ensureJobExists(UUID jobId) {
        if (!jobRepository.existsById(jobId)) {
            throw new MigrationException("Migration job not found", "JOB_NOT_FOUND", 404);
        }
    }
}
