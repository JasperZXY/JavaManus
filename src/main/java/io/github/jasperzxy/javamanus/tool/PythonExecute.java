package io.github.jasperzxy.javamanus.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.jasperzxy.javamanus.exception.ToolError;

/**
 * Python 代码执行工具。
 * 通过 ProcessBuilder 启动独立的 python3 进程执行代码，带超时保护。
 * 对应 OpenManus 的 PythonExecute。
 * 使用独立线程异步读取输出流，waitFor 超时后强制销毁进程。
 */
public class PythonExecute extends BaseTool {

    private static final String DESCRIPTION = """
            Executes Python code string. Note: Only print outputs are visible, \
            function return values are not captured. Use print statements to see results.""";

    private final int defaultTimeoutSeconds;

    public PythonExecute() {
        this(5);
    }

    public PythonExecute(int defaultTimeoutSeconds) {
        super("python_execute", DESCRIPTION, Map.of(
                "type", "object",
                "properties", Map.of(
                        "code", Map.of(
                                "type", "string",
                                "description", "The Python code to execute."
                        ),
                        "timeout", Map.of(
                                "type", "integer",
                                "description", "Execution timeout in seconds."
                        )
                ),
                "required", java.util.List.of("code")
        ));
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    @Override
    public String execute(Map<String, Object> args) {
        Object codeObj = args.get("code");
        if (codeObj == null) {
            throw new ToolError("Parameter 'code' is required");
        }
        String code = codeObj.toString();

        int timeout = defaultTimeoutSeconds;
        Object timeoutObj = args.get("timeout");
        if (timeoutObj instanceof Number) {
            timeout = ((Number) timeoutObj).intValue();
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("javamanus_py_", ".py");
            Files.writeString(tempFile, code, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder("python3", tempFile.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 异步读取输出流，避免阻塞 waitFor，确保超时机制生效
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                // 等待输出流读取完成（进程已被销毁，流会关闭）
                String partialOutput = outputFuture.get(2, TimeUnit.SECONDS);
                String prefix = partialOutput.isEmpty() ? "" : partialOutput + "\n";
                return prefix + "Execution timeout after " + timeout + " seconds";
            }

            String output = outputFuture.get(2, TimeUnit.SECONDS);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return "Exit code: " + exitCode + "\n" + output;
            }
            return output.isEmpty() ? "(no output)" : output;

        } catch (IOException e) {
            throw new ToolError("Failed to execute Python: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolError("Python execution interrupted", e);
        } catch (Exception e) {
            throw new ToolError("Failed to read Python output: " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
