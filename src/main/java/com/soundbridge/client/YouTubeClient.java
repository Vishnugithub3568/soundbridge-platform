package com.soundbridge.client;

import org.springframework.stereotype.Component;

@Component
public class YouTubeClient {

    public YouTubeMatch matchTrack(SpotifyTrack track) {
        int signal = Math.abs((track.getName() + track.getArtist()).hashCode()) % 100;

        if (signal < 70) {
            String targetId = "yt_" + Integer.toHexString((track.getName() + track.getArtist()).hashCode());
            return YouTubeMatch.builder()
                .matched(true)
                .targetTrackId(targetId)
                .targetTrackUrl("https://music.youtube.com/watch?v=" + targetId)
                .confidenceScore(0.80 + (signal / 500.0))
                .partial(false)
                .build();
        }

        if (signal < 88) {
            String targetId = "yt_partial_" + Integer.toHexString((track.getName() + track.getArtist()).hashCode());
            return YouTubeMatch.builder()
                .matched(true)
                .targetTrackId(targetId)
                .targetTrackUrl("https://music.youtube.com/watch?v=" + targetId)
                .confidenceScore(0.55 + (signal / 1000.0))
                .partial(true)
                .failureReason("Matched a close variant")
                .build();
        }

        return YouTubeMatch.builder()
            .matched(false)
            .partial(false)
            .confidenceScore(0.0)
            .failureReason("No reliable YouTube Music match")
            .build();
    }
}
