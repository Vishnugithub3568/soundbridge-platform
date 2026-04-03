package com.soundbridge.dto;

import jakarta.validation.constraints.NotBlank;

public class GoogleTokenExchangeRequest {

    @NotBlank
    private String code;

    @NotBlank
    private String redirectUri;

    @NotBlank
    private String codeVerifier;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getCodeVerifier() {
        return codeVerifier;
    }

    public void setCodeVerifier(String codeVerifier) {
        this.codeVerifier = codeVerifier;
    }
}