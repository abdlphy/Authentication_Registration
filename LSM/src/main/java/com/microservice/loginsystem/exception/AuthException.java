package com.microservice.loginsystem.exception;

public class AuthException extends RuntimeException {
    public AuthException(String message) {
        super(message);
    }
}
