package com.soundbridge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateMigrationRequest {

    @NotBlank
    private String spotifyPlaylistUrl;
}
