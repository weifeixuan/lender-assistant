package com.lender.assistant.ops.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 运维领域：进程存活探针。GET /health 返回 {status, app, time}。
 *
 * 为什么单列 ops 领域而不是塞进 rag/extract：健康检查与业务语义无关，
 * 未来会加入 PG 连通性、熔断器状态等运维视图（ResilientLlmExecutor.circuitState()），
 * 放业务包会被迫反向依赖。compose 验收脚本依赖本端点，不可删除。
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "app", "lender-assistant",
                "time", LocalDateTime.now().toString()
        );
    }
}
