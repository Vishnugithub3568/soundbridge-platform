package com.soundbridge.dto;

public record DashboardServiceStatusResponse(
    String service,
    boolean connected,
    String status,
    String details
) {
}