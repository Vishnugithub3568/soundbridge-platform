package com.soundbridge.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import com.soundbridge.model.MigrationTrack;
import com.soundbridge.model.TrackMatchStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class MigrationRepositoryTest {

    @Autowired
    private MigrationJobRepository jobRepository;

    @Autowired
    private MigrationTrackRepository trackRepository;

    @Test
    void savesAndReadsJobAndTracks() {
        MigrationJob job = new MigrationJob();
        job.setId(UUID.randomUUID());
        job.setSourcePlaylistUrl("https://open.spotify.com/playlist/abc");
        job.setTargetPlatform("YOUTUBE_MUSIC");
        job.setStatus(JobStatus.RUNNING);
        job.setTotalTracks(2);
        job.setMatchedTracks(1);
        job.setFailedTracks(1);
        job = jobRepository.save(job);

        MigrationTrack track = new MigrationTrack();
        track.setJob(job);
        track.setSourceTrackName("Dreams");
        track.setSourceArtistName("Fleetwood Mac");
        track.setMatchStatus(TrackMatchStatus.MATCHED);
        track.setConfidenceScore(0.91);
        track.setTargetTrackId("abc123");
        track.setTargetTrackUrl("https://music.youtube.com/watch?v=abc123");
        track.setTargetTrackTitle("Dreams (Official Video)");
        track.setTargetThumbnailUrl("https://img.youtube.com/vi/abc123/mqdefault.jpg");
        trackRepository.save(track);

        List<MigrationTrack> found = trackRepository.findByJobIdOrderByIdAsc(job.getId());
        assertEquals(1, found.size());
        assertEquals("Dreams (Official Video)", found.get(0).getTargetTrackTitle());
    }
}
