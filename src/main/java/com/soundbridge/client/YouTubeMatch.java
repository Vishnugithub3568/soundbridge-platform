package com.soundbridge.client;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class YouTubeMatch {
    boolean matched;
    String targetTrackId;
    String targetTrackUrl;
    double confidenceScore;
    boolean partial;
    String failureReason;
}
