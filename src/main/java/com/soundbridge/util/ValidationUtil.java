package com.soundbridge.util;

import com.soundbridge.exception.MigrationException;
import java.util.UUID;

public class ValidationUtil {

    public static void validateNotNull(Object value, String fieldName) {
        if (value == null) {
            throw new MigrationException(
                fieldName + " is required",
                "VALIDATION_ERROR",
                400
            );
        }
    }

    public static void validateNotBlank(String value, String fieldName) {
        validateNotNull(value, fieldName);
        if (value.isBlank()) {
            throw new MigrationException(
                fieldName + " cannot be blank",
                "VALIDATION_ERROR",
                400
            );
        }
    }

    public static void validateUUID(String value, String fieldName) {
        validateNotBlank(value, fieldName);
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new MigrationException(
                fieldName + " is not a valid UUID",
                "INVALID_UUID",
                400
            );
        }
    }

    public static void validateMinLength(String value, int minLength, String fieldName) {
        validateNotNull(value, fieldName);
        if (value.length() < minLength) {
            throw new MigrationException(
                fieldName + " must be at least " + minLength + " characters",
                "VALIDATION_ERROR",
                400
            );
        }
    }

    public static void validateRange(double value, double min, double max, String fieldName) {
        if (value < min || value > max) {
            throw new MigrationException(
                fieldName + " must be between " + min + " and " + max,
                "VALIDATION_ERROR",
                400
            );
        }
    }
}
