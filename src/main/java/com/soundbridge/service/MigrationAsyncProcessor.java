package com.soundbridge.service;

import com.soundbridge.client.SpotifyClient;
import com.soundbridge.client.SpotifyTrack;
import com.soundbridge.client.YouTubeCandidate;
import com.soundbridge.client.YouTubeClient;
import com.soundbridge.client.YouTubeMusicClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@SuppressWarnings("null")
public class MigrationAsyncProcessor {

    private static final Logger log = LoggerFactory.getLogger(MigrationAsyncProcessor.class);
    private static final Set<String> MISMATCH_KEYWORDS = Set.of("remix", "live", "cover", "karaoke");
    private static final double TITLE_WEIGHT = 0.7;
    private static final double ARTIST_WEIGHT = 0.3;
    private static final String LOW_CONFIDENCE_FALLBACK_REASON =
        "LOW_CONFIDENCE_FALLBACK: best candidate accepted to keep migration reliable";
    private static final String NO_CANDIDATE_FALLBACK_REASON =
        "SAFE_FALLBACK: no candidates returned, linked YouTube Music search result";

    private final MigrationJobRepository jobRepository;
    private final MigrationTrackRepository trackRepository;
    private final SpotifyClient spotifyClient;
    private final YouTubeClient youTubeClient;
    private final YouTubeMusicClient youTubeMusicClient;
    private final double matchThreshold;
    private final double retryMatchThreshold;

    @Autowired
    public MigrationAsyncProcessor(
        MigrationJobRepository jobRepository,
        MigrationTrackRepository trackRepository,
        SpotifyClient spotifyClient,
        YouTubeClient youTubeClient,
        YouTubeMusicClient youTubeMusicClient,
        @Value("${youtube.match.threshold:0.65}") double matchThreshold,
        @Value("${youtube.retry.match.threshold:0.40}") double retryMatchThreshold
    ) {
        this.jobRepository = jobRepository;
        this.trackRepository = trackRepository;
        this.spotifyClient = spotifyClient;
        this.youTubeClient = youTubeClient;
        this.youTubeMusicClient = youTubeMusicClient;
        this.matchThreshold = matchThreshold;
        this.retryMatchThreshold = retryMatchThreshold;
    }

    public MigrationAsyncProcessor(
        MigrationJobRepository jobRepository,
        MigrationTrackRepository trackRepository,
        SpotifyClient spotifyClient,
        YouTubeClient youTubeClient,
        YouTubeMusicClient youTubeMusicClient
    ) {
        this(jobRepository, trackRepository, spotifyClient, youTubeClient, youTubeMusicClient, 0.65, 0.40);
    }

    public void processMigration(UUID jobId) {
        MigrationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }

        int matched = 0;
        int failed = 0;

        try {
            job.setStatus(JobStatus.RUNNING);
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

            String targetPlaylistId = Objects.requireNonNullElse(job.getTargetPlaylistId(), "").trim();
            if (targetPlaylistId.isBlank()) {
                targetPlaylistId = createTargetPlaylist(job, spotifyDestination, targetAccessToken);
                job.setTargetPlaylistId(targetPlaylistId);
                job.setTargetPlaylistUrl(buildTargetPlaylistUrl(spotifyDestination, targetPlaylistId));
            }

            job.setTotalTracks(sourceTracks.size());
            job.setMatchedTracks(0);
            job.setFailedTracks(0);
            jobRepository.saveAndFlush(job);

            for (SpotifyTrack sourceTrack : sourceTracks) {
                if (sourceTrack == null) {
                    failed++;
                    job.setFailedTracks(failed);
                    jobRepository.saveAndFlush(job);
                    continue;
                }

                MigrationTrack migrationTrack = new MigrationTrack();
                migrationTrack.setJob(job);
                migrationTrack.setSourceTrackName(Objects.requireNonNullElse(sourceTrack.name(), "Unknown Track"));
                migrationTrack.setSourceArtistName(Objects.requireNonNullElse(sourceTrack.artist(), "Unknown Artist"));
                migrationTrack.setSourceAlbumName(sourceTrack.album());

                boolean matchedTrack;
                matchedTrack = spotifyDestination
                    ? processSpotifyDestinationTrack(migrationTrack, sourceTrack, targetPlaylistId, targetAccessToken)
                    : processYouTubeDestinationTrack(migrationTrack, sourceTrack, targetPlaylistId, targetAccessToken);

                if (matchedTrack) {
                    matched++;
                }

                if (!matchedTrack) {
                    failed++;
                }

                trackRepository.save(migrationTrack);

                job.setMatchedTracks(matched);
                job.setFailedTracks(failed);
                jobRepository.saveAndFlush(job);

                try {
                    Thread.sleep(150);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Migration processing interrupted", interruptedException);
                }
            }

            job.setStatus(JobStatus.COMPLETED);
            jobRepository.saveAndFlush(job);
        } catch (Exception ex) {
            int unresolved = Math.max(0, job.getTotalTracks() - matched - failed);
            if (unresolved > 0) {
                failed += unresolved;
            }

            if (job.getTotalTracks() == 0 && failed == 0) {
                MigrationTrack setupFailure = new MigrationTrack();
                setupFailure.setJob(job);
                setupFailure.setSourceTrackName("Migration Setup Failed");
                setupFailure.setSourceArtistName("SoundBridge");
                markTrackAsFailed(setupFailure, "FAILED: " + summarizeError(ex));
                trackRepository.save(setupFailure);
                job.setTotalTracks(1);
                failed = 1;
            }

            job.setMatchedTracks(matched);
            job.setFailedTracks(failed);
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
                if (track.getMatchStatus() != TrackMatchStatus.FAILED) {
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
                    : processYouTubeDestinationTrack(track, sourceTrack, targetPlaylistId, targetAccessToken);

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
        MigrationTrack migrationTrack,
        SpotifyTrack sourceTrack,
        String targetPlaylistId,
        String googleAccessToken
    ) {
        try {
            List<YouTubeCandidate> candidates = youTubeClient.searchCandidates(sourceTrack);
            boolean matchedTrack = applyCandidateMatch(migrationTrack, sourceTrack, candidates, matchThreshold);
            if (!matchedTrack) {
                return false;
            }

            String videoId = Objects.requireNonNullElse(migrationTrack.getYouTubeVideoId(), "").trim();
            if (videoId.isBlank()) {
                markTrackAsPartial(
                    migrationTrack,
                    "PARTIAL: matched for review, but no direct YouTube video id was available for playlist export"
                );
                return true;
            }

            try {
                youTubeClient.addVideoToPlaylist(googleAccessToken, targetPlaylistId, videoId);
                migrationTrack.setMatchStatus(TrackMatchStatus.MATCHED);
                migrationTrack.setFailureReason(null);
            } catch (RuntimeException ex) {
                markTrackAsPartial(
                    migrationTrack,
                    "PARTIAL: matched for review, but could not add track to YouTube playlist: " + summarizeError(ex)
                );
            }

            return true;
        } catch (RuntimeException ex) {
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
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private String buildPlaylistTitle() {
        return "SoundBridge Migration " + Instant.now().toString();
    }

    private ScoredCandidate findBestCandidate(SpotifyTrack sourceTrack, List<YouTubeCandidate> candidates) {
        String normalizedSourceTitle = normalize(sourceTrack.name());
        String normalizedSourceArtist = normalize(sourceTrack.artist());

        return candidates
            .stream()
            .map(candidate -> scoreCandidate(sourceTrack, normalizedSourceTitle, normalizedSourceArtist, candidate))
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
        YouTubeCandidate candidate
    ) {
        String normalizedCandidateTitle = normalize(candidate.title());
        String normalizedCandidateArtist = normalize(candidate.channelTitle());

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

        double finalScore = clamp((TITLE_WEIGHT * titleScore) + (ARTIST_WEIGHT * artistScore) - penalty);

        log.debug(
            "YouTube scoring | source='{}' artist='{}' | candidateId={} title='{}' channel='{}' titleScore={} artistScore={} penalty={} finalScore={} threshold={}",
            sourceTrack.name(),
            sourceTrack.artist(),
            candidate.videoId(),
            candidate.title(),
            candidate.channelTitle(),
            round3(titleScore),
            round3(artistScore),
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
