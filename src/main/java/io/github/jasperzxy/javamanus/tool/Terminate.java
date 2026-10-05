package io.github.jasperzxy.javamanus.tool;

import java.util.Map;

/**
 * 终止交互工具。
 * 对应 OpenManus 的 Terminate。
 */
public class Terminate extends BaseTool {

    private static final String DESCRIPTION = """
            Terminate the interaction when the request is met OR if the assistant cannot proceed further with the task.
            When you have finished all the tasks, call this tool to end the work.""";

    public Terminate() {
        super("terminate", DESCRIPTION, Map.of(
                "type", "object",
                "properties", Map.of(
                        "status", Map.of(
                                "type", "string",
                                "description", "The finish status of the interaction.",
                                "enum", java.util.List.of("success", "failure")
                        )
                ),
                "required", java.util.List.of("status")
        ));
    }

    @Override
    public String execute(Map<String, Object> args) {
        Object status = args.get("status");
        return "The interaction has been completed with status: " + status;
    }
}
