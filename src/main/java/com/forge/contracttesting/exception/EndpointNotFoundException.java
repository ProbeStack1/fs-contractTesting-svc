package com.forge.contracttesting.exception;

public class EndpointNotFoundException extends RuntimeException {
    public EndpointNotFoundException(String message) {
        super(message);
    }
}
