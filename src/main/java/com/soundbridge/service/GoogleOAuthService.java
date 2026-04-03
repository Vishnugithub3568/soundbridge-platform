package com.soundbridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Service
@SuppressWarnings("null")
public class GoogleOAuthService {

    private static final Set<String> YOUTUBE_WRITE_SCOPES = Set.of(
        "https://www.googleapis.com/auth/youtube.force-ssl",
        "https://www.googleapis.com/auth/youtube"
    );

    private final RestTemplate restTemplate;
    private final String clientId;
    private final String clientSecret;
    private final String tokenUrl;

    public GoogleOAuthService(
        RestTemplateBuilder restTemplateBuilder,
        @Value("${google.client-id:}") String clientId,
        @Value("${google.client-secret:}") String clientSecret,
        @Value("${google.token-url:https://oauth2.googleapis.com/token}") String tokenUrl
    ) {
        this.restTemplate = restTemplateBuilder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tokenUrl = tokenUrl;
    }

    public Map<String, Object> exchangeAuthorizationCode(String code, String redirectUri, String codeVerifier) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException("Google OAuth server credentials are missing. Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET on backend.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code_verifier", codeVerifier);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);

        ResponseEntity<JsonNode> response;
        try {
            response = restTemplate.exchange(tokenUrl, HttpMethod.POST, request, JsonNode.class);
        } catch (HttpStatusCodeException ex) {
            String details = ex.getResponseBodyAsString();
            throw new IllegalArgumentException(details == null || details.isBlank() ? "Google token exchange failed" : details);
        }

        JsonNode body = Objects.requireNonNull(response.getBody(), "Google token response is empty");
        String accessToken = body.path("access_token").asText("");
        if (accessToken.isBlank()) {
            throw new IllegalArgumentException("Google token response did not include access_token");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("access_token", accessToken);
        payload.put("expires_in", body.path("expires_in").asLong(3600L));
        payload.put("token_type", body.path("token_type").asText("Bearer"));

        String idToken = body.path("id_token").asText("");
        if (!idToken.isBlank()) {
            payload.put("id_token", idToken);
        }

        String scope = body.path("scope").asText("");
        if (!scope.isBlank()) {
            payload.put("scope", scope);
        }

        String refreshToken = body.path("refresh_token").asText("");
        if (!refreshToken.isBlank()) {
            payload.put("refresh_token", refreshToken);
        }

        return payload;
    }

    public boolean hasYouTubeWriteScope(String accessToken) {
        String token = Objects.requireNonNullElse(accessToken, "").trim();
        if (token.isBlank()) {
            return false;
        }

        String url = "https://www.googleapis.com/oauth2/v3/tokeninfo?access_token=" + token;
        ResponseEntity<JsonNode> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, null, JsonNode.class);
        } catch (HttpStatusCodeException ex) {
            return false;
        }

        JsonNode body = response.getBody();
        String scope = body == null ? "" : body.path("scope").asText("");
        if (scope.isBlank()) {
            return false;
        }

        String[] tokens = scope.split("\\s+");
        for (String scopeToken : tokens) {
            if (YOUTUBE_WRITE_SCOPES.contains(scopeToken)) {
                return true;
            }
        }

        return false;
    }
}