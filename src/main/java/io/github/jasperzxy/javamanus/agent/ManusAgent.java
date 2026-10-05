package io.github.jasperzxy.javamanus.agent;

import java.util.List;

import org.springframework.ai.chat.model.ChatModel;

import io.github.jasperzxy.javamanus.config.JavaManusProperties;
import io.github.jasperzxy.javamanus.prompt.ManusPrompt;
import io.github.jasperzxy.javamanus.tool.AskHuman;
import io.github.jasperzxy.javamanus.tool.PythonExecute;
import io.github.jasperzxy.javamanus.tool.StrReplaceEditor;
import io.github.jasperzxy.javamanus.tool.Terminate;
import io.github.jasperzxy.javamanus.tool.ToolCollection;

/**
 * Manus 主 Agent，装配通用工具集。
 * 对应 OpenManus 的 Manus。
 */
public class ManusAgent extends ToolCallAgent {

    public ManusAgent(ChatModel chatModel, JavaManusProperties props) {
        super(chatModel, "Manus",
                "A versatile agent that can solve various tasks using multiple tools");

        this.systemPrompt = ManusPrompt.systemPrompt(props.getWorkspaceRoot());
        this.nextStepPrompt = ManusPrompt.NEXT_STEP_PROMPT;
        this.maxSteps = props.getMaxSteps();
        this.maxObserve = props.getMaxObserve();
        this.duplicateThreshold = props.getDuplicateThreshold();

        this.availableTools = new ToolCollection(
                new PythonExecute(),
                new StrReplaceEditor(),
                new AskHuman(),
                new Terminate()
        );
        this.specialToolNames = List.of("terminate");
    }
}
