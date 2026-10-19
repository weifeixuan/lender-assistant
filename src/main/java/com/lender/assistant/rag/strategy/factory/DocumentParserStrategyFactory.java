package com.lender.assistant.rag.strategy.factory;

import com.lender.assistant.rag.strategy.DocumentParserStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档解析策略工厂：按文件名在运行时从多个 {@link DocumentParserStrategy} 中选出能处理的那一个。
 *
 * 为什么解析器要工厂、而其余策略不要：
 * - 其余 5 个策略是**构建期 provider 二选一**（@ConditionalOnProperty），容器里始终只有一个 Bean，
 *   直接注入接口即可，多写一个工厂就是死抽象；
 * - 解析器是**运行时按格式分发**——同一时刻多个实现都在容器里，必须有一次显式选择。
 *   把选择逻辑收在这一处，调用方不再各自写 stream 过滤或 if/else（分发口径单一事实源）。
 */
@Component
public class DocumentParserStrategyFactory {

    private final List<DocumentParserStrategy> strategies;

    public DocumentParserStrategyFactory(List<DocumentParserStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * 返回支持该文件名的解析策略。
     * 没有命中说明是当前不支持的格式，抛可读异常由全局异常处理转成业务错误。
     */
    public DocumentParserStrategy resolve(String fileName) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(fileName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支持的文件类型: " + fileName + "（当前支持 .docx/.txt/.md）"));
    }
}
