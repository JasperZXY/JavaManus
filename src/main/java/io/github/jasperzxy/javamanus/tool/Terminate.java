package io.github.jasperzxy.javamanus.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 终止交互工具。
 * 对应 OpenManus 的 Terminate。
 */
public class Terminate {

    private static final String DESCRIPTION = """
            Terminate the interaction when the request is met OR if the assistant cannot proceed further with the task.
            When you have finished all the tasks, call this tool to end the work.""";

    @Tool(name = "terminate", description = DESCRIPTION)
    public String terminate(
            @ToolParam(description = "The finish status of the interaction.", required = true) String status) {
        return "The interaction has been completed with status: " + status;
    }
}
