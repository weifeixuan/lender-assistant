package com.lender.assistant.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 控制器日志切面：打印每个 REST 接口的入参、返回值与耗时，异常时落 ERROR + 堆栈（§5）。
 * 入参/出参统一 JSON 打印（容器 ObjectMapper，与响应序列化同一份配置——日志即真实响应），
 * 特殊类型例外：MultipartFile 只记元信息、SseEmitter 只记占位（流式响应无法整体打印）。
 *
 * 顺序契约：@Order(1) 在外层，ResponseWrapAspect @Order(2) 在内层。进入时先记入参；
 * proceed() 返回的已是内层包装好的 ApiResponse，出参日志天然含 code/msg/data——
 * 即"日志记录的对象就是真正写出的响应体"，本切面不参与任何包装。
 * Spring 对无 @Order 的多个切面不保证相对顺序，两个值必须显式声明（见 ResponseWrapAspect）。
 *
 * 只切 @RestController（含类级注解）——切面只负责"HTTP 边界"的可观测性，不越界切业务内部；
 * service / strategy 的日志由各自代码按需打印。
 *
 * 脱敏：入参若是 Map（请求体的常见形态）按 key 名脱敏（password/token/secret/key/idCard/bankCard → &lt;masked&gt;），
 * 脱敏发生在 JSON 序列化之前；出参由服务端生成、非外部输入，不做脱敏。
 *
 * 序列化兜底：个别对象 getter 抛异常 / 循环引用会导致 Jackson 序列化失败，必须 catch 降级为
 * String.valueOf——日志组件绝不能因序列化问题影响业务请求。
 *
 * 截断策略：超长 JSON 整体省略、只留原始长度。半截 JSON 无法被日志采集端解析，
 * 与其留"看似可解析实则残缺"的文本，不如干净给出长度指标。
 *
 * 已知边界：当前控制器入参只有 Map（可脱敏）与不含敏感字段的 record（JSON 序列化）。
 * 若未来新增"含敏感字段的 record 入参"，需把脱敏扩展为按 record 字段名处理。
 */
@Aspect
@Component
@Order(1)
public class ControllerLogAspect {

    private static final Logger log = LoggerFactory.getLogger(ControllerLogAspect.class);

    /** 单条日志记录值上限：防止大返回值把日志刷爆 */
    private static final int MAX_LOG_CHARS = 500;
    /** 慢接口阈值（毫秒）：仅作日志提示、不改变响应行为，故用常量而非配置项 */
    private static final long SLOW_REQUEST_MILLIS = 1000L;
    private static final String MASKED = "<masked>";
    /** 命中任一片段即脱敏（key 先小写、去分隔符再 contains 匹配） */
    private static final Set<String> SENSITIVE_KEY_FRAGMENTS =
            Set.of("password", "passwd", "token", "secret", "key", "idcard", "bankcard");

    /** 容器注入：与响应序列化共用同一份 ObjectMapper 配置，保证日志 JSON 与 HTTP body 格式一致 */
    private final ObjectMapper objectMapper;

    public ControllerLogAspect(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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
            // 内层 ResponseWrapAspect 已包装，此处打出的就是真正写出的响应体
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

    private String describe(Object value) {
        if (value == null) {
            return "null";
        }
        // 参数列表转成 JSON 数组形态，每个元素独立过一遍特殊类型处理
        Object jsonSafe = value instanceof Object[] array ? arrayAsJsonSafe(array) : jsonSafe(value);
        return truncateLog(toJson(jsonSafe));
    }

    private static List<Object> arrayAsJsonSafe(Object[] array) {
        List<Object> list = new ArrayList<>(array.length);
        for (Object element : array) {
            list.add(jsonSafe(element));
        }
        return list;
    }

    /**
     * 特殊类型替换成 JSON 可描述的替身：文件只留元信息、流式响应只记占位、Map 先按 key 脱敏；
     * 其余类型原样交给 ObjectMapper 序列化。
     */
    private static Object jsonSafe(Object value) {
        if (value instanceof MultipartFile file) {
            return Map.of("fileName", String.valueOf(file.getOriginalFilename()), "size", file.getSize());
        }
        if (value instanceof SseEmitter) {
            return "SseEmitter(流式响应，不记录内容)";
        }
        if (value instanceof Map<?, ?> map) {
            return maskMap(map);
        }
        return value;
    }

    /** 序列化失败必须降级：getter 抛异常 / 循环引用等场景回退 toString，不影响业务请求 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
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

    /** 超长整体省略、只留原始长度：半截 JSON 对采集端无意义 */
    private static String truncateLog(String json) {
        return json.length() <= MAX_LOG_CHARS ? json : "...(超长省略,原始长度=" + json.length() + "字符)";
    }
}
