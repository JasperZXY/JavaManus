package io.github.jasperzxy.javamanus.tool;

import java.util.Map;
import java.util.Scanner;

/**
 * 询问人类工具。
 * 对应 OpenManus 的 AskHuman。
 * SSE 模式下暂返回提示信息，CLI 模式下读控制台。
 */
public class AskHuman extends BaseTool {

    private static final String DESCRIPTION = "Use this tool to ask human for help.";

    public AskHuman() {
        super("ask_human", DESCRIPTION, Map.of(
                "type", "object",
                "properties", Map.of(
                        "inquire", Map.of(
                                "type", "string",
                                "description", "The question you want to ask human."
                        )
                ),
                "required", java.util.List.of("inquire")
        ));
    }

    @Override
    public String execute(Map<String, Object> args) {
        Object inquire = args.get("inquire");
        String question = inquire != null ? inquire.toString() : "";

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
