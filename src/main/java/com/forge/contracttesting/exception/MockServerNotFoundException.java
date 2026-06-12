package com.forge.contracttesting.exception;

public class MockServerNotFoundException extends RuntimeException {
    public MockServerNotFoundException(String message) {
        super(message);
    }
}
