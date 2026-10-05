package io.github.jasperzxy.javamanus.exception;

/**
 * 工具执行异常
 */
public class ToolError extends RuntimeException {

    public ToolError(String message) {
        super(message);
    }

    public ToolError(String message, Throwable cause) {
        super(message, cause);
    }
}
