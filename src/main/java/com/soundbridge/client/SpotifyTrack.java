package com.soundbridge.client;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SpotifyTrack {
    String name;
    String artist;
    String album;
}
