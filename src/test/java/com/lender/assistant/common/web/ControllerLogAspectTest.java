package com.lender.assistant.common.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lender.assistant.common.dto.ApiResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ControllerLogAspect 日志格式测试：入参/出参 JSON 化、Map 脱敏发生在序列化之前。
 * 用 Logback ListAppender 捕获真实日志行，验证的是最终打印内容而非中间方法。
 */
class ControllerLogAspectTest {

    private final ControllerLogAspect aspect = new ControllerLogAspect(new com.fasterxml.jackson.databind.ObjectMapper());
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    static class SampleController {
        public Map<String, Object> normal() {
            return Map.of();
        }
    }

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(ControllerLogAspect.class);
        appender.list.clear();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private static ProceedingJoinPoint joinPointOf(Object result, Object... args) throws Throwable {
        MethodSignature signature = org.mockito.Mockito.mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(SampleController.class.getMethod("normal"));
        when(signature.getDeclaringType()).thenReturn(SampleController.class);
        when(signature.getName()).thenReturn("normal");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.proceed()).thenReturn(result);
        return joinPoint;
    }

    private String loggedLine(String keyword) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.contains(keyword))
                .findFirst()
                .orElse("");
    }

    @Test
    @DisplayName("出参：内层包装后的 ApiResponse 被打成 JSON，code/msg/data 可检索")
    void logsResponseAsJson() throws Throwable {
        aspect.logControllerCall(joinPointOf(ApiResponse.ok(Map.of("docId", "x"))));

        String line = loggedLine("接口调用返回");
        assertTrue(line.contains("\"code\":200"), "出参应为 JSON 且含 code 字段，实际: " + line);
        assertTrue(line.contains("\"msg\":\"ok\""), "出参应为 JSON 且含 msg 字段，实际: " + line);
        assertTrue(line.contains("\"docId\":\"x\""), "data 内容应原样进入 JSON，实际: " + line);
    }

    @Test
    @DisplayName("入参：Map 序列化为 JSON，敏感 key 在序列化前被脱敏")
    void logsArgsAsMaskedJson() throws Throwable {
        aspect.logControllerCall(joinPointOf(null, Map.of("password", "p1", "question", "q")));

        String line = loggedLine("接口调用进入");
        assertTrue(line.contains("\"password\":\"<masked>\""), "敏感 key 应脱敏后再 JSON 化，实际: " + line);
        assertTrue(line.contains("\"question\":\"q\""), "非敏感 key 应原样保留，实际: " + line);
    }
}
