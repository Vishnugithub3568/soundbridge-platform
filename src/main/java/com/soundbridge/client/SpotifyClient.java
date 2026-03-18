package com.soundbridge.client;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SpotifyClient {

    public List<SpotifyTrack> fetchPlaylistTracks(String playlistUrl) {
        // Deterministic mock data for local development until real Spotify integration is wired.
        return List.of(
            SpotifyTrack.builder().name("Dreams").artist("Fleetwood Mac").album("Rumours").build(),
            SpotifyTrack.builder().name("Blinding Lights").artist("The Weeknd").album("After Hours").build(),
            SpotifyTrack.builder().name("Levitating").artist("Dua Lipa").album("Future Nostalgia").build(),
            SpotifyTrack.builder().name("Numb").artist("Linkin Park").album("Meteora").build(),
            SpotifyTrack.builder().name("Halo").artist("Beyonce").album("I Am... Sasha Fierce").build(),
            SpotifyTrack.builder().name("Midnight City").artist("M83").album("Hurry Up, We Are Dreaming").build()
        );
    }
}
