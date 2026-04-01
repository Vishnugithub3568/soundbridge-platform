package com.soundbridge.service;

import com.soundbridge.client.SpotifyClient;
import com.soundbridge.client.SpotifyTrack;
import com.soundbridge.client.YouTubeCandidate;
import com.soundbridge.client.YouTubeClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Comparator;
import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import com.soundbridge.model.MigrationTrack;
import com.soundbridge.model.TrackMatchStatus;
import java.util.Locale;
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
    private final double matchThreshold;
    private final double retryMatchThreshold;

    @Autowired
    public MigrationAsyncProcessor(
        MigrationJobRepository jobRepository,
        MigrationTrackRepository trackRepository,
        SpotifyClient spotifyClient,
        YouTubeClient youTubeClient,
        @Value("${youtube.match.threshold:0.65}") double matchThreshold,
        @Value("${youtube.retry.match.threshold:0.40}") double retryMatchThreshold
    ) {
        this.jobRepository = jobRepository;
        this.trackRepository = trackRepository;
        this.spotifyClient = spotifyClient;
        this.youTubeClient = youTubeClient;
        this.matchThreshold = matchThreshold;
        this.retryMatchThreshold = retryMatchThreshold;
    }

    public MigrationAsyncProcessor(
        MigrationJobRepository jobRepository,
        MigrationTrackRepository trackRepository,
        SpotifyClient spotifyClient,
        YouTubeClient youTubeClient
    ) {
        this(jobRepository, trackRepository, spotifyClient, youTubeClient, 0.65, 0.40);
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

            List<SpotifyTrack> sourceTracks = spotifyClient.fetchPlaylistTracks(
                job.getSourcePlaylistUrl(),
                job.getSpotifyAccessToken()
            );
            if (sourceTracks == null) {
                sourceTracks = List.of();
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
                try {
                    List<YouTubeCandidate> candidates = youTubeClient.searchCandidates(sourceTrack);
                    matchedTrack = applyCandidateMatch(migrationTrack, sourceTrack, candidates, matchThreshold);
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
                    matchedTrack = true;
                }

                if (matchedTrack) {
                    matched++;
                } else {
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

                try {
                    List<YouTubeCandidate> candidates = youTubeClient.searchCandidates(sourceTrack);
                    applyCandidateMatch(track, sourceTrack, candidates, retryMatchThreshold);
                } catch (RuntimeException ex) {
                    applySearchFallbackMatch(
                        track,
                        sourceTrack,
                        "SAFE_FALLBACK: YouTube lookup unavailable on retry, linked search result"
                    );
                    log.warn(
                        "Retry lookup failed for source='{}' artist='{}' reason={}",
                        sourceTrack.name(),
                        sourceTrack.artist(),
                        summarizeError(ex)
                    );
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
        int matched = (int) tracks.stream().filter(track -> track.getMatchStatus() == TrackMatchStatus.MATCHED).count();
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

    private String summarizeError(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
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
