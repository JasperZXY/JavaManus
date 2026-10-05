package io.github.jasperzxy.javamanus.tool;

import java.util.Scanner;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 询问人类工具。
 * 对应 OpenManus 的 AskHuman。
 * SSE 模式下暂返回提示信息，CLI 模式下读控制台。
 */
public class AskHuman {

    @Tool(name = "ask_human", description = "Use this tool to ask human for help.")
    public String askHuman(
            @ToolParam(description = "The question you want to ask human.", required = true) String inquire) {
        String question = inquire != null ? inquire : "";

        // CLI 模式：从控制台读取
        if (System.console() != null) {
            System.out.println("\nBot: " + question);
            System.out.print("You: ");
            try (Scanner scanner = new Scanner(System.in)) {
                return scanner.nextLine().trim();
            }
        }

        // 非 CLI（如 SSE）模式：返回提示
        return "[Need human input] " + question + " (请在 CLI 模式下使用此工具)";
    }
}
