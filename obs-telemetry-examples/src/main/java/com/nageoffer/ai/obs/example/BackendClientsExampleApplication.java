package com.nageoffer.ai.obs.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;

/**
 * 后端客户端接口使用示例的启动入口。
 *
 * <p>仅做演示：默认不执行写操作。{@code obs.example.enabled=true} 时由 {@link ExampleRunner}
 * 执行只读演示（列数据集条目、按 traceId 查日志/span）。写操作（关联 run item / 提交评分）
 * 的用法见 {@link LangfuseDatasetExample} 的参考实现与模块 README。</p>
 */
@SpringBootApplication
public class BackendClientsExampleApplication {

    /**
     * 启动示例应用。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BackendClientsExampleApplication.class);
        // 示例不需要 Web 服务，只跑 ApplicationRunner 演示后退出
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }
}
