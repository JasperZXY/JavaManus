package io.github.jasperzxy.javamanus.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.jasperzxy.javamanus.exception.ToolError;

/**
 * 工具集合，管理多个工具并提供调度能力。
 * 对应 OpenManus 的 ToolCollection。
 */
public class ToolCollection {

    private final Map<String, BaseTool> toolMap = new LinkedHashMap<>();

    public ToolCollection(BaseTool... tools) {
        for (BaseTool tool : tools) {
            addTool(tool);
        }
    }

    public ToolCollection addTool(BaseTool tool) {
        if (toolMap.containsKey(tool.getName())) {
            return this;
        }
        toolMap.put(tool.getName(), tool);
        return this;
    }

    public ToolCollection addTools(BaseTool... tools) {
        for (BaseTool tool : tools) {
            addTool(tool);
        }
        return this;
    }

    public BaseTool getTool(String name) {
        return toolMap.get(name);
    }

    public List<BaseTool> getTools() {
        return new ArrayList<>(toolMap.values());
    }

    public boolean contains(String name) {
        return toolMap.containsKey(name);
    }

    /**
     * 执行指定工具。
     *
     * @param name 工具名
     * @param args 参数
     * @return 工具输出
     */
    public String execute(String name, Map<String, Object> args) {
        BaseTool tool = toolMap.get(name);
        if (tool == null) {
            throw new ToolError("Unknown tool: " + name);
        }
        return tool.execute(args != null ? args : new HashMap<>());
    }
}
