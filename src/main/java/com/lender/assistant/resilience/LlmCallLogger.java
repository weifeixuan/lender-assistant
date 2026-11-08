package com.lender.assistant.resilience;

import com.lender.assistant.common.constant.LlmScene;
import com.lender.assistant.common.util.ExceptionMessageUtils;
import com.lender.assistant.resilience.mapper.LlmCallLogMapper;
import com.lender.assistant.resilience.model.entity.LlmCallLogEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Repository;

import java.util.function.Supplier;

/**
 * 模型调用留痕器（每次模型调用留痕——耗时/token/成本）。
 *
 * 设计：装饰器/旁路模式——业务代码只写 logger.record(scene, provider, () -> ...)，
 * 留痕对主链路零侵入；且留痕写库失败绝不反噬业务（旁路降级）。
 *
 * 这是简历数字"单次接入推理成本 XX 元"的数据来源：
 * llm_call_log 按行累计 prompt/completion token，成本 = token × 单价（配置价目）。
 * Mock 调用 token 记 NULL——mock 没有真实计量，诚实留空比编个数字好。
 *
 * 数据访问已迁到 LlmCallLogMapper（§11）：本类只保留"包住一次调用 + 旁路降级"的编排职责，
 * 不再持有 JdbcTemplate。旁路降级（catch 后不上抛）是刻意的——见 write()。
 */
@Repository
public class LlmCallLogger {

    private static final Logger log = LoggerFactory.getLogger(LlmCallLogger.class);

    private final LlmCallLogMapper mapper;
    /**
     * 当前调用线程内的 token 计量：真实模型实现在 record() 包裹的 supplier 内回填，
     * record() 落库时取走并清理——避免线程池复用把上一轮计量带进下一次调用。
     */
    private final ThreadLocal<TokenUsage> capturedUsage = new ThreadLocal<>();

    public LlmCallLogger(LlmCallLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 包住一次模型调用：成功记 OK，失败记 FAIL 并原样上抛异常（不吞不换壳） */
    public <T> T record(LlmScene scene, String provider, Supplier<T> call) {
        // 清掉线程复用可能残留的上一轮计量，保证本行只记本次调用
        capturedUsage.remove();
        long t0 = System.currentTimeMillis();
        try {
            T result = call.get();
            TokenUsage usage = takeUsage();
            write(scene, provider, (int) (System.currentTimeMillis() - t0),
                    usage == null ? null : usage.promptTokens(),
                    usage == null ? null : usage.completionTokens(), "OK", null);
            return result;
        } catch (RuntimeException e) {
            // 失败行必须留下可读原因：框架包装异常的 message 可能为 null（原因在 cause 里），
            // 直接写 e.getMessage() 会让 error_msg 变成空值，事后无法解释这次失败
            TokenUsage usage = takeUsage();
            write(scene, provider, (int) (System.currentTimeMillis() - t0),
                    usage == null ? null : usage.promptTokens(),
                    usage == null ? null : usage.completionTokens(),
                    "FAIL", ExceptionMessageUtils.describe(e));
            throw e;
        }
    }

    /**
     * 真实模型实现回填 token 计量（同一调用线程、在 record() 包裹的 supplier 内）。
     * 拿不到 Usage 就不填：留 NULL 比编数字诚实（Mock 调用同理）。
     */
    public void captureUsage(Integer promptTokens, Integer completionTokens) {
        if (promptTokens == null && completionTokens == null) {
            return;
        }
        capturedUsage.set(new TokenUsage(promptTokens, completionTokens));
    }

    /** 从 Spring AI 响应读取 Usage；流式场景只有末块可能带 Usage，取最后一次非空值 */
    public void captureUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            captureUsage(usage.getPromptTokens(), usage.getCompletionTokens());
        }
    }

    private TokenUsage takeUsage() {
        TokenUsage usage = capturedUsage.get();
        capturedUsage.remove();
        return usage;
    }

    private void write(LlmScene scene, String provider, int durationMs, Integer promptTokens,
                       Integer completionTokens, String status, String errorMsg) {
        try {
            mapper.insert(new LlmCallLogEntity(scene.code(), provider, durationMs,
                    promptTokens, completionTokens, status, errorMsg));
        } catch (RuntimeException e) {
            // 留痕是旁路：log 表写不进去（没跑 docs/schema.sql 等）不允许拖垮问答/抽取主链路。
            // 但必须 WARN 可见，否则失败调用"没有记录"将无法解释
            log.warn("LLM 调用留痕写入失败（旁路，不阻断业务）: scene={}, status={}, reason={}",
                    scene.code(), status, e.getMessage(), e);
        }
    }

    private record TokenUsage(Integer promptTokens, Integer completionTokens) {
    }
}
