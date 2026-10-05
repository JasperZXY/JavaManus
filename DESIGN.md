# JavaManus 设计方案

> 用 Java 21 + Maven + Spring Boot + Spring AI 复刻 [OpenManus](https://github.com/FoundationAgents/OpenManus)，LLM 底层使用字节火山引擎 Ark。

## 一、项目坐标

| 项 | 值 |
|----|----|
| GroupId | `io.github.jasperzxy` |
| ArtifactId | `javamanus` |
| 包名 | `io.github.jasperzxy.javamanus` |
| JDK | 21 |
| Spring Boot | 3.4.4 |
| Spring AI | 1.1.0 |
| 构建 | Maven |

## 二、技术选型

| 组件 | 选型 | 说明 |
|------|------|------|
| 构建 | Maven | 多模块管理 |
| JDK | Java 21 | 虚拟线程支持异步 Agent 循环 |
| 框架 | Spring Boot 3.4.4 + Spring AI 1.1.0 | 复用 Spring AI ChatModel / ToolCallback 抽象 |
| LLM SDK | volcengine-java-sdk-ark-runtime 2.0.0 | 复用现有 ArkChatModel 实现 |
| JSON | Jackson（Spring Boot 默认） | 工具参数解析 |
| 简化 | Lombok 1.18.46 | 减少样板代码 |
| 调试接口 | Spring MVC SSE (`SseEmitter`) | 流式推送 Agent 思考与工具执行过程 |

## 三、整体架构

```
io.github.jasperzxy.javamanus
├── JavaManusApplication.java          # Spring Boot 启动类
├── config/
│   └── JavaManusProperties.java       # 全局配置(工作目录/最大步数/最大观察长度等)
├── volcengine/                        # ← 复用现有火山引擎实现
│   ├── ArkProperties.java
│   ├── ArkChatModel.java
│   ├── ArkEmbeddingModel.java
│   └── ArkAutoConfiguration.java
├── schema/                            # 数据模型（对应 app/schema.py）
│   ├── AgentState.java                # IDLE/RUNNING/FINISHED/ERROR
│   ├── ToolChoice.java                # NONE/AUTO/REQUIRED
│   └── Memory.java                    # 消息列表 + 上限
├── agent/                             # Agent 体系（对应 app/agent/）
│   ├── BaseAgent.java                 # 抽象基类：状态机+步数循环+卡死检测
│   ├── ReActAgent.java                # think() + act() 模式
│   ├── ToolCallAgent.java             # 工具调用编排核心
│   └── ManusAgent.java                # Manus 主 Agent，装配工具集
├── tool/                              # 工具体系（对应 app/tool/）
│   ├── BaseTool.java                  # 工具抽象（name/description/parameters/execute）
│   ├── ToolResult.java                # 执行结果
│   ├── ToolCollection.java            # 工具集合 + 调度
│   ├── Terminate.java                 # 终止工具
│   ├── PythonExecute.java             # Python 执行（ProcessBuilder + 超时）
│   ├── StrReplaceEditor.java          # 文件查看/创建/替换/插入/撤销
│   └── AskHuman.java                  # 询问人类
├── prompt/                            # 提示词（对应 app/prompt/）
│   ├── ManusPrompt.java
│   └── ToolCallPrompt.java
├── event/
│   ├── AgentEvent.java                # Agent 事件（thought/tool_call/tool_result/complete）
│   └── AgentEventListener.java        # 事件监听接口（供 SSE Controller 实现）
├── exception/
│   ├── ToolError.java
│   └── TokenLimitExceeded.java
└── controller/
    └── ManusController.java           # SSE 调试接口
```

## 四、核心模块设计

### 1. LLM 调用层（关键设计决策）

OpenManus 自行实现 `LLM.ask_tool()` 并手动解析 tool_calls。Spring AI 的 `ChatModel` 已具备工具调用机制，因此 JavaManus 采取折中方案：

**复用 Spring AI 的工具定义（ToolCallback / ToolDefinition），但禁用内部自动执行，由 Agent 手动控制循环。**

- 现有 `ArkChatModel` 已实现：当 `ToolCallingChatOptions.isInternalToolExecutionEnabled()` 为 `false` 时，`call()` 仅返回带 `toolCalls` 的 `AssistantMessage`，不会自动执行工具。
- Agent 的 `think()`：
  1. 组装 `Prompt`（system prompt + memory 中的消息）
  2. 设置 `DefaultToolCallingChatOptions`（工具列表 + `internalToolExecutionEnabled=false`）
  3. 调用 `chatModel.call(prompt)`
  4. 从返回的 `AssistantMessage` 提取 `toolCalls`
- Agent 的 `act()`：
  1. 遍历 `toolCalls`，在 `ToolCollection` 中找到对应工具
  2. 解析 JSON 参数
  3. 执行工具
  4. 把结果包装成 `ToolResponseMessage` 加入 memory
  5. 若是 `terminate` 等特殊工具，设置 `state = FINISHED`
- 循环回到 `think()`，直到模型不再调用工具或触发 `terminate`。

**优势**：既复用了 Spring AI 的标准工具定义机制，又保持了 OpenManus 的 Agent 自主控制循环。

### 2. Agent 状态机（对应 BaseAgent）

```
BaseAgent (abstract)
├── state: AgentState (IDLE/RUNNING/FINISHED/ERROR)
├── memory: Memory (List<Message>)
├── maxSteps: int (默认 20)
├── currentStep: int
├── duplicateThreshold: int (卡死检测阈值)
├── run(request): 主循环
│   ├── 校验 state == IDLE
│   ├── 添加 user message 到 memory
│   ├── while currentStep < maxSteps && state != FINISHED:
│   │   ├── step() → think() + act()
│   │   └── isStuck() 检测重复响应 → 注入提示词
│   └── 返回结果
└── step(): 抽象方法，由子类实现
```

### 3. ReAct + ToolCall 编排（对应 react.py + toolcall.py）

```
ReActAgent (abstract) extends BaseAgent
├── think(): boolean  → 决定下一步是否需要 act
└── act(): String     → 执行动作并返回结果

ToolCallAgent (abstract) extends ReActAgent
├── availableTools: ToolCollection
├── toolCalls: List<AssistantMessage.ToolCall>
├── think():
│   ├── 调用 chatModel.call(prompt)  [禁用内部工具执行]
│   ├── 解析 response 中的 toolCalls
│   ├── 记录 assistant message 到 memory
│   ├── 发布 thought 事件（content）
│   └── 返回是否需要 act
├── act():
│   ├── 遍历 toolCalls → executeTool()
│   │   ├── 发布 tool_call 事件
│   │   ├── ToolCollection.execute(name, args)
│   │   ├── 发布 tool_result 事件
│   │   └── 结果写入 memory (ToolResponseMessage)
│   ├── 检测特殊工具(terminate) → state = FINISHED
│   └── 返回拼接结果
└── executeTool(): JSON 解析参数 + 异常处理
```

### 4. ManusAgent（对应 manus.py）

```
ManusAgent extends ToolCallAgent
├── systemPrompt = "You are OpenManus, an all-capable AI assistant... 初始目录: {workspace}"
├── nextStepPrompt = "主动选择最适当的工具... 用 terminate 结束"
├── availableTools = ToolCollection(
│       PythonExecute, StrReplaceEditor, AskHuman, Terminate
│   )
├── specialToolNames = ["terminate"]
└── maxSteps = 20
```

### 5. 工具体系（对应 tool/base.py + tool_collection.py）

```java
// BaseTool 实现 Spring AI 的 ToolCallback 接口，
// 既能被 ChatModel 识别为工具定义，又能由 Agent 手动调用 execute()
public abstract class BaseTool implements ToolCallback {
    protected String name;
    protected String description;
    protected Map<String, Object> parameters;  // JSON Schema

    public abstract ToolResult execute(Map<String, Object> args);

    // ToolCallback 实现
    @Override public ToolResult call(ToolCallingContext context) {
        return execute(context.getArguments());
    }
    @Override public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(name).description(description)
            .inputSchema(toJsonSchema(parameters))
            .build();
    }
}
```

工具实现：

| 工具 | 说明 | 实现方式 |
|------|------|----------|
| `Terminate` | 终止交互，参数 status(success/failure) | 直接返回状态字符串，触发 Agent 完成 |
| `PythonExecute` | 执行 Python 代码 | `ProcessBuilder` 启动 `python3 -c`，捕获 stdout/stderr，超时默认 5s |
| `StrReplaceEditor` | 文件查看/创建/替换/插入/撤销 | Java NIO，支持 view/create/str_replace/insert/undo_edit |
| `AskHuman` | 询问人类 | CLI 模式下读控制台；SSE 模式下暂返回提示（后续可扩展） |

**工具参数 Schema**：手写 `Map<String, Object>` 表示 JSON Schema，贴近 OpenManus 且可控。

### 6. SSE 调试接口

```
POST /api/manus/chat
Content-Type: application/json
{ "prompt": "帮我写一个 hello world 的 Python 脚本" }

响应: text/event-stream
  event: thought      → Agent 的思考内容
  event: tool_call    → 调用的工具名 + 参数
  event: tool_result  → 工具执行结果
  event: complete     → 最终结果，连接关闭
```

实现要点：
- `ManusController` 注入 `ManusAgent`（原型作用域，每次请求新建实例）
- 返回 `SseEmitter`（超时设为较大值，如 5 分钟）
- `ManusController` 实现 `AgentEventListener`，在回调中 `emitter.send(SseEmitter.event().name(...).data(...))`
- Agent 在虚拟线程中执行，避免阻塞 Web 容器线程
- 完成或异常时 `emitter.complete()` / `emitter.completeWithError()`

### 7. 事件机制

```java
public interface AgentEventListener {
    void onThought(String content);
    void onToolCall(String toolName, Map<String, Object> args);
    void onToolResult(String toolName, String result);
    void onComplete(String finalResult);
    void onError(Throwable e);
}
```

`ToolCallAgent` 维护 `List<AgentEventListener>`，在 think/act 各阶段发布事件。

### 8. 配置（对应 config.py）

用 `application.yml` 替代 TOML：

```yaml
server:
  port: 18080

spring:
  ai:
    volcengine:
      base-url: https://ark.cn-beijing.volces.com/api/v3
      api-key: ark-xxx
      chat-model: deepseek-v4-flash-ga-260731
      temperature: 0.1

javamanus:
  workspace-root: ./workspace
  max-steps: 20
  max-observe: 10000
```

## 五、核心执行流程

```
用户 POST /api/manus/chat { prompt }
    ↓
ManusController → ManusAgent.run(prompt) [虚拟线程]
    ↓
BaseAgent.run() 循环:
    ├─ ToolCallAgent.think()
    │   ├─ 组装 Prompt (system + memory)
    │   ├─ chatModel.call() [禁用内部工具执行]
    │   ├─ 解析 toolCalls / content
    │   ├─ assistant message 写入 memory
    │   └─ 发布 thought 事件 → SSE
    ├─ ToolCallAgent.act()
    │   ├─ 对每个 toolCall:
    │   │   ├─ 发布 tool_call 事件 → SSE
    │   │   ├─ ToolCollection.execute(name, args)
    │   │   ├─ 发布 tool_result 事件 → SSE
    │   │   ├─ 结果包装为 ToolResponseMessage → memory
    │   │   └─ 若 terminate → state = FINISHED
    │   └─ 返回结果
    └─ isStuck() 检测重复响应
    ↓
发布 complete 事件 → SSE 关闭
```

## 六、与 OpenManus 的差异

| 维度 | OpenManus | JavaManus |
|------|-----------|-----------|
| 异步 | `asyncio` | 虚拟线程 + `CompletableFuture` |
| 工具执行 | 自解析 LLM 返回的 tool_calls | 借助 Spring AI `ChatModel` 返回结构化 `toolCalls`，**禁用自动执行**，Agent 手动调度 |
| Python 执行 | `multiprocessing` | `ProcessBuilder` 启动独立 `python3` 进程，带超时终止 |
| 配置 | TOML | `application.yml` + `@ConfigurationProperties` |
| 交互入口 | CLI (`main.py`) | SSE HTTP 接口 + 可选 CLI |
| 消息类型 | 自定义 `Message` | Spring AI 的 `Message` / `AssistantMessage` / `ToolResponseMessage` |

## 七、开发阶段（里程碑）

| 阶段 | 内容 | 验证标准 |
|------|------|----------|
| **P0 骨架** | pom.xml、启动类、配置类、Ark 模型接入 | 启动成功，能调用火山引擎返回文本 |
| **P1 数据模型** | schema、exception、event | 编译通过 |
| **P2 工具体系** | BaseTool/ToolResult/ToolCollection + Terminate/AskHuman | 工具可独立调用 |
| **P3 Agent 核心** | BaseAgent/ReActAgent/ToolCallAgent 状态机与循环 | Agent 能跑通 think→act 循环 |
| **P4 Manus 装配** | ManusAgent + PythonExecute + StrReplaceEditor + 提示词 | 端到端：输入任务→工具执行→终止 |
| **P5 SSE 接口** | ManusController + 事件推送 | curl 调用能收到 thought/tool_call/tool_result/complete 事件 |

## 八、目录结构预览

```
JavaManus/
├── pom.xml
├── DESIGN.md
├── workspace/                          # Agent 工作目录
└── src/main/
    ├── java/io/github/jasperzxy/javamanus/
    │   ├── JavaManusApplication.java
    │   ├── config/
    │   ├── volcengine/
    │   ├── schema/
    │   ├── agent/
    │   ├── tool/
    │   ├── prompt/
    │   ├── event/
    │   ├── exception/
    │   └── controller/
    └── resources/
        └── application.yml
```

## 九、依赖清单

```xml
<parent>spring-boot-starter-parent 3.4.4</parent>

<dependencies>
    spring-boot-starter-web          # MVC + SSE
    spring-ai-model                  # ChatModel / ToolCallback 抽象
    volcengine-java-sdk-ark-runtime 2.0.0
    lombok 1.18.46 (provided)
    spring-boot-starter-test (test)
</dependencies>

<repositories>
    spring-milestones (https://repo.spring.io/milestone)
</repositories>
```
