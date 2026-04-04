package com.soundbridge.service;

import com.soundbridge.dto.CreateMigrationRequest;
import com.soundbridge.dto.MigrationJobResponse;
import com.soundbridge.dto.MigrationPreflightRequest;
import com.soundbridge.dto.MigrationPreflightResponse;
import com.soundbridge.dto.MigrationReportResponse;
import com.soundbridge.dto.MigrationTrackResponse;
import com.soundbridge.client.YouTubeMusicClient;
import com.soundbridge.exception.MigrationException;
import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import com.soundbridge.repository.MigrationJobRepository;
import com.soundbridge.repository.MigrationTrackRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressWarnings("null")
public class MigrationService {

    private static final Pattern SPOTIFY_PLAYLIST_PATTERN = Pattern.compile("open\\.spotify\\.com/playlist/([A-Za-z0-9]+)");

    private final MigrationJobRepository jobRepository;
    private final MigrationTrackRepository trackRepository;
    private final MigrationAsyncProcessor migrationAsyncProcessor;
    private final ApplicationContext applicationContext;
    private final GoogleOAuthService googleOAuthService;
    private final YouTubeMusicClient youTubeMusicClient;

    public MigrationService(
        MigrationJobRepository jobRepository,
        MigrationTrackRepository trackRepository,
        MigrationAsyncProcessor migrationAsyncProcessor,
        ApplicationContext applicationContext,
        GoogleOAuthService googleOAuthService,
        YouTubeMusicClient youTubeMusicClient
    ) {
        this.jobRepository = jobRepository;
        this.trackRepository = trackRepository;
        this.migrationAsyncProcessor = migrationAsyncProcessor;
        this.applicationContext = applicationContext;
        this.googleOAuthService = googleOAuthService;
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

        return new MigrationPreflightResponse(
            validUrl,
            readyToStart,
            direction,
            sourcePlatform,
            targetPlatform,
            playlistId,
            blockers,
            recommendations
        );
    }

    private MigrationJobResponse startSpotifyToYouTubeMigration(CreateMigrationRequest request) {
        String spotifyPlaylistUrl = Objects.requireNonNullElse(request.getSourcePlaylistUrl(), "").trim();
        if (spotifyPlaylistUrl.isEmpty()) {
            throw new MigrationException("Spotify playlist URL is required", "MISSING_PLAYLIST_URL", 400);
        }

        String spotifyAccessToken = Objects.requireNonNullElse(request.getSpotifyAccessToken(), "").trim();
        String googleAccessToken = Objects.requireNonNullElse(request.getGoogleAccessToken(), "").trim();

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
        job.setTargetPlatform("YOUTUBE_MUSIC");
        job.setStatus(JobStatus.QUEUED);
        job.setTotalTracks(0);
        job.setMatchedTracks(0);
        job.setFailedTracks(0);
        job = jobRepository.saveAndFlush(job);

        UUID jobId = job.getId();
        applicationContext.getBean(MigrationService.class).processMigrationAsync(jobId);
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
        job.setTargetPlatform("SPOTIFY");
        job.setStatus(JobStatus.QUEUED);
        job.setTotalTracks(0);
        job.setMatchedTracks(0);
        job.setFailedTracks(0);
        job = jobRepository.saveAndFlush(job);

        UUID jobId = job.getId();
        applicationContext.getBean(MigrationService.class).processMigrationAsync(jobId);
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
        job = jobRepository.saveAndFlush(job);

        UUID jobId = job.getId();
        applicationContext.getBean(MigrationService.class).processMigrationAsync(jobId);
        return MigrationJobResponse.from(job);
    }

    public MigrationJobResponse retryFailedTracks(UUID jobId) {
        MigrationJob job = getJobEntity(jobId);
        if (job.getStatus() == JobStatus.RUNNING || job.getStatus() == JobStatus.QUEUED) {
            throw new MigrationException("Migration job is already in progress", "JOB_IN_PROGRESS", 409);
        }

        if (job.getFailedTracks() <= 0) {
            return MigrationJobResponse.from(job);
        }

        job.setStatus(JobStatus.QUEUED);
        jobRepository.saveAndFlush(job);
        applicationContext.getBean(MigrationService.class).retryFailedTracksAsync(jobId);
        return MigrationJobResponse.from(job);
    }

    @Async("migrationTaskExecutor")
    public void processMigrationAsync(UUID jobId) {
        migrationAsyncProcessor.processMigration(jobId);
    }

    @Async("migrationTaskExecutor")
    public void retryFailedTracksAsync(UUID jobId) {
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

        return new MigrationReportResponse(
            job.getId(),
            job.getStatus(),
            job.getTotalTracks(),
            job.getMatchedTracks(),
            job.getFailedTracks(),
            matchRate,
            tracks
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

    private void ensureJobExists(UUID jobId) {
        if (!jobRepository.existsById(jobId)) {
            throw new MigrationException("Migration job not found", "JOB_NOT_FOUND", 404);
        }
    }
}
