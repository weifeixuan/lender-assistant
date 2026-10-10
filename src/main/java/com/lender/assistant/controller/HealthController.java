package com.lender.assistant.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 存活探针：验证应用能起、能响应。
 * 不用 actuator，保持依赖最小；后续需要更细健康检查（PG 连通等）再扩充这里。
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
