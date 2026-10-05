# JavaManus

用 Java 21 + Maven + Spring Boot + Spring AI 复刻 [OpenManus](https://github.com/FoundationAgents/OpenManus) 的通用 Agent 框架，底层 LLM 使用字节火山引擎 Ark。

## 技术栈

| 组件 | 版本 |
|------|------|
| JDK | 21 |
| Spring Boot | 3.4.4 |
| Spring AI | 1.1.0 |
| 构建工具 | Maven |
| LLM SDK | volcengine-java-sdk-ark-runtime 2.0.0 |
| 日志 | SLF4J + Logback |

## 模块结构

```
src/main/java/io/github/jasperzxy/javamanus/
├── JavaManusApplication.java      # 启动类
├── config/                        # 配置（JavaManusProperties、AgentConfig）
├── volcengine/                    # 火山引擎 Ark 适配（ChatModel / EmbeddingModel）
├── schema/                        # 数据模型（AgentState、ToolChoice、Memory）
├── agent/                         # Agent 体系（BaseAgent → ReActAgent → ToolCallAgent → ManusAgent）
├── tool/                          # 工具体系（BaseTool、ToolCollection + 4 个内置工具）
├── prompt/                        # 提示词
├── event/                         # 事件监听（供 SSE 推送）
├── exception/                     # 异常
└── controller/                    # controller接口
```

## 内置工具

| 工具 | 说明 |
|------|------|
| `str_replace_editor` | 文件查看/创建/替换/插入/撤销 |
| `python_execute` | 执行 Python 代码（ProcessBuilder + 超时） |
| `ask_human` | 询问人类 |
| `terminate` | 终止交互 |

## 环境要求

- JDK 21+
- Maven 3.8+
- 系统安装 `python3`（`python_execute` 工具依赖）
- 火山引擎 Ark API Key

## 配置说明

### application.yml（基础配置）

```yaml
server:
  port: 18080

javamanus:
  workspace-root: /tmp/javamanus/workspace   # Agent 工作目录
  max-steps: 20                               # 最大执行步数
  max-observe: 10000                          # 工具结果最大观察长度
  duplicate-threshold: 2                      # 卡死检测阈值
```

### application-local.yml（本地密钥，不提交到仓库）

```yaml
spring:
  ai:
    volcengine:
      api-key: ark-你的真实密钥
      chat-model: deepseek-v4-flash-ga-260731
```

> `application-local.yml` 仅用于本地开发，覆盖 `application.yml` 中的占位配置。

## 本地启动

```bash
mvn compile spring-boot:run -Dspring-boot.run.profiles=local
```

启动成功后服务监听 `http://localhost:18080`。

日志同时输出到控制台和 `./logs/javamanus.log`。

## SSE 调试接口

### 请求

```
curl -N -X POST http://localhost:18080/api/manus/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt":"用Python写一个代码，斐波那契数列输出，10行数列即可，代码写完并执行，把结果输出"}'
```

### 响应（text/event-stream）

| 事件 | 说明 |
|------|------|
| `thought` | Agent 思考内容 |
| `tool_call` | 调用的工具 `{name, args}` |
| `tool_result` | 工具执行结果 `{name, result}` |
| `complete` | 最终结果，连接关闭 |
| `error` | 错误信息 |



## 二次开发

JavaManus 采用与 OpenManus 一致的分层设计，方便二次开发复用。以下是常见的扩展方式：

### 1. 自定义工具

继承 `BaseTool`，实现 `execute()` 方法，指定 name / description / parameters（JSON Schema）：

```java
public class MyTool extends BaseTool {

    public MyTool() {
        super("my_tool", "工具描述", Map.of(
                "type", "object",
                "properties", Map.of(
                        "arg1", Map.of("type", "string", "description", "参数说明")
                ),
                "required", List.of("arg1")
        ));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String arg1 = args.get("arg1").toString();
        // 你的业务逻辑
        return "执行结果";
    }
}
```

然后在自定义 Agent 的 `availableTools` 中注册：

```java
this.availableTools = new ToolCollection(
        new MyTool(),
        new Terminate()
);
```

### 2. 自定义 Agent

继承 `ToolCallAgent`，设置 systemPrompt、nextStepPrompt、工具集：

```java
public class MyAgent extends ToolCallAgent {

    public MyAgent(ChatModel chatModel, JavaManusProperties props) {
        super(chatModel, "MyAgent", "自定义 Agent 描述");
        this.systemPrompt = "你的系统提示词";
        this.nextStepPrompt = "下一步提示词";
        this.maxSteps = props.getMaxSteps();
        this.availableTools = new ToolCollection(new MyTool(), new Terminate());
        this.specialToolNames = List.of("terminate");
    }
}
```

在 `AgentConfig` 中注册为 prototype Bean：

```java
@Bean
@Scope("prototype")
public MyAgent myAgent(ChatModel arkChatModel, JavaManusProperties props) {
    return new MyAgent(arkChatModel, props);
}
```

### 3. 替换 LLM

`BaseAgent` 依赖 Spring AI 的 `ChatModel` 接口，Agent 层与具体模型解耦。当前默认使用火山引擎 Ark（`ArkChatModel`，Bean 名为 `arkChatModel`）。接入其他模型有两种方式：

#### 方式一：使用 Spring AI 官方 Starter（推荐）

Spring AI 官方提供了多个模型的 Starter，直接引入依赖并配置即可。

以 OpenAI 兼容接口（如 OpenAI、DeepSeek、Moonshot、智谱等）为例：

**1. 添加依赖**（`pom.xml`）：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-openai</artifactId>
</dependency>
```

**2. 配置**（`application-local.yml`）：

```yaml
spring:
  ai:
    openai:
      api-key: sk-你的密钥
      base-url: https://api.openai.com/v1   # 或兼容地址，如 DeepSeek: https://api.deepseek.com
      chat:
        options:
          model: gpt-4o-mini
```

**3. 替换 Bean 注入**：在 `AgentConfig` 中把 `arkChatModel` 改为注入 Spring AI 自动配置的 `openAiChatModel`：

```java
@Bean
@Scope("prototype")
public ManusAgent manusAgent(ChatModel openAiChatModel, JavaManusProperties props) {
    return new ManusAgent(openAiChatModel, props);
}
```

> 其他官方 Starter 同理，如 `spring-ai-starter-ollama`（本地模型）、`spring-ai-starter-dashscope`（通义千问）、`spring-ai-starter-azure-openai` 等。

#### 方式二：自定义 ChatModel 实现（适配任意 HTTP API）

如果目标模型没有官方 Starter，参考 `ArkChatModel` 的写法，实现 Spring AI 的 `ChatModel` 接口：

```java
public class MyChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        // 1. 把 prompt 中的 Message 列表转为目标 API 的请求格式
        // 2. 发起 HTTP 请求
        // 3. 把响应转为 AssistantMessage（含 toolCalls）返回
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return DefaultToolCallingChatOptions.builder().build();
    }
}
```

然后在 `@Configuration` 中注册为 Bean（Bean 名保持 `arkChatModel` 即可直接复用，无需改 `AgentConfig`）：

```java
@Bean(name = "arkChatModel")
public ChatModel myChatModel() {
    return new MyChatModel();
}
```

> 无论哪种方式，Agent 层代码都无需改动。

### 4. 自定义提示词

提示词集中在 `prompt/` 包下，直接修改对应常量或模板即可。

### 5. 扩展交互入口

当前提供 SSE 接口（`ManusController`）。如需 CLI、WebSocket、定时任务等入口，注入对应 Agent 的 `ObjectProvider`，调用 `agent.run(prompt)`，并通过 `addListener()` 监听执行过程。

## 设计参考

- [OpenManus](https://github.com/FoundationAgents/OpenManus)
