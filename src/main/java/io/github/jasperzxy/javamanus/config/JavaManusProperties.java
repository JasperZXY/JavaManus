package io.github.jasperzxy.javamanus.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "javamanus")
public class JavaManusProperties {

    /** Agent 工作目录 */
    private String workspaceRoot = "./workspace";

    /** 最大执行步数 */
    private int maxSteps = 20;

    /** 工具结果最大观察长度（字符数），0 表示不截断 */
    private int maxObserve = 10000;

    /** 卡死检测：重复响应次数阈值 */
    private int duplicateThreshold = 2;
}
