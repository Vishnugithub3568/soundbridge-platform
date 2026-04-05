package com.soundbridge.exception;

public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message, Throwable cause) {
        super(message, cause);
    }

    public QuotaExceededException(String message) {
        super(message);
    }
}
