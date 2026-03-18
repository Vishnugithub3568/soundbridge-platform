package com.soundbridge.integration;

import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.soundbridge.client.SpotifyClient;
import com.soundbridge.client.SpotifyTrack;
import com.soundbridge.client.YouTubeClient;
import com.soundbridge.client.YouTubeMatch;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class MigrationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SpotifyClient spotifyClient;

    @MockBean
    private YouTubeClient youTubeClient;

    @Test
    void migrateFlowEndpointsReturnData() throws Exception {
        when(spotifyClient.fetchPlaylistTracks(any())).thenReturn(List.of(
            new SpotifyTrack("Dreams", "Fleetwood Mac", "Rumours"),
            new SpotifyTrack("Numb", "Linkin Park", "Meteora")
        ));

        when(youTubeClient.matchTrack(any())).thenReturn(new YouTubeMatch(
            true,
            "abc123",
            "https://music.youtube.com/watch?v=abc123",
            "Dreams (Official Video)",
            "https://img.youtube.com/vi/abc123/mqdefault.jpg",
            0.9,
            false,
            null
        ));

        MvcResult createResult = mockMvc.perform(post("/migrate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"spotifyPlaylistUrl\":\"https://open.spotify.com/playlist/abc\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.id", notNullValue()))
            .andReturn();

        String body = createResult.getResponse().getContentAsString();
        String jobId = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        Thread.sleep(600);

        mockMvc.perform(get("/migrate/{jobId}", jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalTracks").value(2));

        mockMvc.perform(get("/migrate/{jobId}/tracks", jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].targetTrackTitle").value("Dreams (Official Video)"));

        mockMvc.perform(get("/migrate/{jobId}/report", jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matchedTracks").value(2));
    }
}
