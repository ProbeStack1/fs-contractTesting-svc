package com.forge.contracttesting.exception;

public class EndpointAlreadyExistsException extends RuntimeException {
    public EndpointAlreadyExistsException(String message) {
        super(message);
    }
}
