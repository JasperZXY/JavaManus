package io.github.jasperzxy.javamanus.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.model.ChatModel;

import io.github.jasperzxy.javamanus.config.JavaManusProperties;
import io.github.jasperzxy.javamanus.prompt.ManusPrompt;
import io.github.jasperzxy.javamanus.tool.AskHuman;
import io.github.jasperzxy.javamanus.tool.PythonExecute;
import io.github.jasperzxy.javamanus.tool.StrReplaceEditor;
import io.github.jasperzxy.javamanus.tool.Terminate;
import io.github.jasperzxy.javamanus.tool.ToolCollection;
import lombok.extern.slf4j.Slf4j;

/**
 * Manus 主 Agent，装配通用工具集。
 * 对应 OpenManus 的 Manus。
 * <p>
 * 工作区隔离：每次实例化（prototype 作用域，每次请求新建）都会在共享 workspace-root
 * 下生成一个 UUID 子目录作为本次运行的独立工作区，避免并发请求互相覆盖文件。
 */
@Slf4j
public class ManusAgent extends ToolCallAgent {

    public ManusAgent(ChatModel chatModel, JavaManusProperties props) {
        super(chatModel, "Manus",
                "A versatile agent that can solve various tasks using multiple tools");

        // 在共享 workspace-root 下生成 UUID 子目录，实现每次请求的文件系统隔离
        Path baseWorkspace = props.getWorkspaceRoot() != null
                ? Path.of(props.getWorkspaceRoot())
                : null;
        Path runWorkspace = baseWorkspace != null
                ? baseWorkspace.resolve(UUID.randomUUID().toString())
                : null;

        if (runWorkspace != null) {
            try {
                Files.createDirectories(runWorkspace);
                log.info("Created isolated workspace: {}", runWorkspace);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create workspace: " + runWorkspace, e);
            }
        }

        String workspacePath = runWorkspace != null ? runWorkspace.toString() : null;
        this.systemPrompt = ManusPrompt.systemPrompt(workspacePath);
        this.nextStepPrompt = ManusPrompt.NEXT_STEP_PROMPT;
        this.maxSteps = props.getMaxSteps();
        this.maxObserve = props.getMaxObserve();
        this.duplicateThreshold = props.getDuplicateThreshold();
        this.memory.setMaxMessages(props.getMaxMessages());

        this.availableTools = new ToolCollection(
                new PythonExecute(),
                new StrReplaceEditor(runWorkspace),
                new AskHuman(),
                new Terminate()
        );
        this.specialToolNames = List.of("terminate");
    }
}
