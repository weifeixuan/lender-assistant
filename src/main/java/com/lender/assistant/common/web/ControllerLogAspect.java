package com.lender.assistant.common.web;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 控制器日志切面：打印每个 REST 接口的入参、返回值与耗时，异常时落 ERROR + 堆栈（§5）。
 *
 * 只切 @RestController（含类级注解）——切面只负责"HTTP 边界"的可观测性，不越界切业务内部；
 * service / strategy 的日志由各自代码按需打印。
 *
 * 脱敏：入参若是 Map（请求体的常见形态）按 key 名脱敏（password/token/secret/key/idCard/bankCard → &lt;masked&gt;）；
 * 出参由服务端生成、非外部输入，不做脱敏。SseEmitter 只记类型不记内容（流式响应无法整体打印）。
 *
 * 已知边界：当前控制器入参只有 Map（可脱敏）与不含敏感字段的 record（按 toString 打印）。
 * 若未来新增"含敏感字段的 record 入参"，需把脱敏扩展为按 record 字段名处理。
 */
@Aspect
@Component
public class ControllerLogAspect {

    private static final Logger log = LoggerFactory.getLogger(ControllerLogAspect.class);

    /** 单次记录值上限：防止大返回值把日志刷爆 */
    private static final int MAX_LOG_CHARS = 500;
    /** 慢接口阈值（毫秒）：仅作日志提示、不改变响应行为，故用常量而非配置项 */
    private static final long SLOW_REQUEST_MILLIS = 1000L;
    private static final String MASKED = "<masked>";
    /** 命中任一片段即脱敏（key 先小写、去分隔符再 contains 匹配） */
    private static final Set<String> SENSITIVE_KEY_FRAGMENTS =
            Set.of("password", "passwd", "token", "secret", "key", "idcard", "bankcard");

    @Pointcut("within(@org.springframework.web.bind.annotation.RestController *)")
    public void restControllerMethods() {
    }

    @Around("restControllerMethods()")
    public Object logControllerCall(ProceedingJoinPoint joinPoint) throws Throwable {
        String api = describeApi(joinPoint);
        long start = System.currentTimeMillis();
        log.info("接口调用进入 {} 入参={}", api, describe(joinPoint.getArgs()));
        try {
            Object result = joinPoint.proceed();
            long cost = System.currentTimeMillis() - start;
            log.info("接口调用返回 {} 耗时={}ms 出参={}", api, cost, describe(result));
            if (cost >= SLOW_REQUEST_MILLIS) {
                log.warn("慢接口 {} 耗时={}ms（阈值 {}ms）", api, cost, SLOW_REQUEST_MILLIS);
            }
            return result;
        } catch (Throwable e) {
            // ERROR + 堆栈：接口在边界处失败，这是定位请求方与入参的完整证据
            log.error("接口调用异常 {} 耗时={}ms", api, System.currentTimeMillis() - start, e);
            throw e;
        }
    }

    /** 接口标识：类简名 + 方法名，便于日志里直接定位是哪个端点 */
    private static String describeApi(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getDeclaringType().getSimpleName() + "#" + signature.getName();
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Object[] array) {
            StringJoiner joiner = new StringJoiner(", ", "[", "]");
            for (Object element : array) {
                joiner.add(describe(element));
            }
            return truncate(joiner.toString());
        }
        if (value instanceof MultipartFile file) {
            return "MultipartFile(name=" + file.getOriginalFilename() + ", size=" + file.getSize() + ")";
        }
        if (value instanceof SseEmitter) {
            return "SseEmitter(流式响应，不记录内容)";
        }
        if (value instanceof Map<?, ?> map) {
            return truncate(maskMap(map).toString());
        }
        return truncate(String.valueOf(value));
    }

    private static Map<String, Object> maskMap(Map<?, ?> source) {
        Map<String, Object> masked = new LinkedHashMap<>();
        source.forEach((key, value) ->
                masked.put(String.valueOf(key), isSensitiveKey(String.valueOf(key)) ? MASKED : value));
        return masked;
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SENSITIVE_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    private static String truncate(String text) {
        return text.length() <= MAX_LOG_CHARS ? text : text.substring(0, MAX_LOG_CHARS) + "...(已截断)";
    }
}
