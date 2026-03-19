package com.soundbridge.controller;

import com.soundbridge.model.User;
import com.soundbridge.service.AuthService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/sync-user")
    public ResponseEntity<Map<String, Object>> syncUser(@RequestBody Map<String, String> request) {
        UUID userId;
        try {
            userId = UUID.fromString(request.get("userId"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid userId format"));
        }

        String email = request.get("email");
        String displayName = request.get("displayName");

        if (email == null || email.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Email is required"));
        }

        User user = authService.getOrCreateUser(userId, email, displayName);
        return ResponseEntity.ok(Map.of(
            "id", user.getId(),
            "email", user.getEmail(),
            "displayName", user.getDisplayName(),
            "createdAt", user.getCreatedAt()
        ));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<?> getUser(@PathVariable UUID userId) {
        return authService.getUserById(userId)
            .map(user -> ResponseEntity.ok((Object) Map.of(
                "id", user.getId(),
                "email", user.getEmail(),
                "displayName", user.getDisplayName(),
                "createdAt", user.getCreatedAt()
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/user/{userId}/display-name")
    public ResponseEntity<Map<String, Object>> updateDisplayName(
        @PathVariable UUID userId,
        @RequestBody Map<String, String> request
    ) {
        String displayName = request.get("displayName");
        if (displayName == null || displayName.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "displayName is required"));
        }

        User user = authService.updateUserDisplayName(userId, displayName);
        return ResponseEntity.ok(Map.of(
            "id", user.getId(),
            "email", user.getEmail(),
            "displayName", user.getDisplayName(),
            "updatedAt", user.getUpdatedAt()
        ));
    }
}
