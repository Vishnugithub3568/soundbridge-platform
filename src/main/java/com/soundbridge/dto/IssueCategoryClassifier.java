package com.soundbridge.dto;

import com.soundbridge.model.TrackMatchStatus;
import java.util.Locale;

public final class IssueCategoryClassifier {

    private IssueCategoryClassifier() {
    }

    public static String categorize(TrackMatchStatus status, String failureReason) {
        String reason = String.valueOf(failureReason == null ? "" : failureReason).trim();
        String normalized = reason.toLowerCase(Locale.ROOT);

        if (status == TrackMatchStatus.NOT_FOUND
            || normalized.contains("no match found")
            || normalized.contains("no spotify match found")) {
            return "NO_MATCH";
        }

        if (normalized.contains("quota") || normalized.contains("rate limit")) {
            return "QUOTA";
        }

        if (normalized.contains("permission")
            || normalized.contains("scope")
            || normalized.contains("access_denied")
            || normalized.contains("forbidden")) {
            return "PERMISSION";
        }

        if (normalized.contains("token")
            || normalized.contains("unauthorized")
            || normalized.contains("expired")
            || normalized.contains("invalid_grant")) {
            return "TOKEN";
        }

        if (normalized.contains("timeout")
            || normalized.contains("temporary")
            || normalized.contains("network")
            || normalized.contains("connection")
            || normalized.contains("service unavailable")
            || normalized.contains("internal server error")) {
            return "TRANSIENT";
        }

        if (status == TrackMatchStatus.PARTIAL || normalized.startsWith("partial:")) {
            return "PARTIAL";
        }

        if (normalized.startsWith("safe_fallback:") || normalized.startsWith("low_confidence_fallback:")) {
            return "FALLBACK";
        }

        if (status == TrackMatchStatus.FAILED) {
            return "UNKNOWN";
        }

        return "NONE";
    }

    public static String recommendedAction(String issueCategory) {
        return switch (String.valueOf(issueCategory == null ? "" : issueCategory).trim().toUpperCase(Locale.ROOT)) {
            case "QUOTA" -> "Wait for quota reset, then retry the failed tracks.";
            case "PERMISSION" -> "Reconnect the account and grant the requested OAuth permissions.";
            case "TOKEN" -> "Reconnect the account to refresh expired or invalid tokens.";
            case "TRANSIENT" -> "Retry now; temporary network or API instability is likely.";
            case "NO_MATCH" -> "Review track title and artist metadata, then retry the unmatched tracks.";
            case "PARTIAL" -> "Inspect partial tracks and retry export to recover missing destination adds.";
            case "UNKNOWN" -> "Inspect failure details and retry after checking provider status.";
            default -> "No retry action required.";
        };
    }
}
