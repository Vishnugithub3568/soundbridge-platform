package com.soundbridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.soundbridge.client.SpotifyClient;
import com.soundbridge.client.SpotifyTrack;
import com.soundbridge.client.YouTubeCandidate;
import com.soundbridge.client.YouTubeClient;
import com.soundbridge.client.YouTubeMusicClient;
import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import com.soundbridge.model.TrackMatchStatus;
import com.soundbridge.model.MigrationTrack;
import com.soundbridge.repository.MigrationJobRepository;
import com.soundbridge.repository.MigrationTrackRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class MigrationAsyncProcessorTest {

    @Mock
    private MigrationJobRepository jobRepository;

    @Mock
    private MigrationTrackRepository trackRepository;

    @Mock
    private SpotifyClient spotifyClient;

    @Mock
    private YouTubeClient youTubeClient;

    @Mock
    private YouTubeMusicClient youTubeMusicClient;

    private MigrationAsyncProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new MigrationAsyncProcessor(jobRepository, trackRepository, spotifyClient, youTubeClient, youTubeMusicClient);
    }

    @Test
    void processMigrationCompletesAndPersistsTrackData() {
        UUID jobId = UUID.randomUUID();
        MigrationJob job = new MigrationJob();
        job.setId(jobId);
        job.setSourcePlaylistUrl("https://open.spotify.com/playlist/abc");
        job.setGoogleAccessToken("google-token");

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.saveAndFlush(any(MigrationJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(trackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(spotifyClient.fetchPlaylistTracks(anyString(), any())).thenReturn(List.of(new SpotifyTrack("Dreams", "Fleetwood Mac", "Rumours", 257800L)));
        when(youTubeClient.searchCandidates(any())).thenReturn(List.of(
            new YouTubeCandidate(
                "abc123",
                "Dreams",
                "Fleetwood Mac",
                "https://img.youtube.com/vi/abc123/mqdefault.jpg"
            )
        ));

        processor.processMigration(jobId);

        verify(jobRepository, atLeastOnce()).saveAndFlush(any(MigrationJob.class));

        assertEquals(JobStatus.COMPLETED, job.getStatus());
        assertEquals(1, job.getMatchedTracks());
        assertEquals(0, job.getFailedTracks());
        verify(trackRepository).save(any());
    }

    @Test
    void processMigrationKeepsReviewableTrackAsPartialWhenNoDirectVideoIdExists() {
        UUID jobId = UUID.randomUUID();
        MigrationJob job = new MigrationJob();
        job.setId(jobId);
        job.setSourcePlaylistUrl("https://open.spotify.com/playlist/abc");
        job.setGoogleAccessToken("google-token");

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.saveAndFlush(any(MigrationJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(trackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(spotifyClient.fetchPlaylistTracks(anyString(), any())).thenReturn(List.of(new SpotifyTrack("Dreams", "Fleetwood Mac", "Rumours", 257800L)));
        when(youTubeClient.searchCandidates(any())).thenReturn(List.of(
            new YouTubeCandidate(
                "",
                "Dreams",
                "Fleetwood Mac",
                "https://img.youtube.com/vi/abc123/mqdefault.jpg"
            )
        ));

        processor.processMigration(jobId);

        ArgumentCaptor<MigrationTrack> trackCaptor = ArgumentCaptor.forClass(MigrationTrack.class);
        verify(trackRepository).save(trackCaptor.capture());

        assertEquals(JobStatus.COMPLETED, job.getStatus());
        assertEquals(1, job.getMatchedTracks());
        assertEquals(0, job.getFailedTracks());
        assertEquals(TrackMatchStatus.PARTIAL, trackCaptor.getValue().getMatchStatus());
    }
}
