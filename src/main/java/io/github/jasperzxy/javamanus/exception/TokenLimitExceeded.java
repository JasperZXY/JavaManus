package io.github.jasperzxy.javamanus.exception;

/**
 * Token 超限异常
 */
public class TokenLimitExceeded extends RuntimeException {

    public TokenLimitExceeded(String message) {
        super(message);
    }
}
