package com.lender.assistant.draft.strategy.impl;

import com.lender.assistant.draft.strategy.DraftGeneratorStrategy;
import com.lender.assistant.extract.model.FieldMapping;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 真实模型草稿生成（OpenAI 兼容协议）：把骨架与映射表交给模型，产出适配层 Java 草稿。
 *
 * Prompt 铁律（呼应 QaService 三铁律的"宁缺勿编"）：
 * - 只依据映射表与骨架生成，映射表没有的信息留 TODO，禁止编造
 * - 只输出 Java 源码（.call().content() 非流式——草稿是整篇产物，无流式需求）
 *
 * 装配条件与 Mock 版互斥：仅在 rag.draft.provider=openai 时创建，Mock 为默认。
 */
@Component
@ConditionalOnProperty(name = "rag.draft.provider", havingValue = "openai")
public class OpenAiCompatibleDraftGeneratorStrategy implements DraftGeneratorStrategy {

    private static final String SYSTEM_PROMPT = """
            你是融资租赁资方接入适配层的代码生成助手。规则：
            1. 只依据给出的字段映射表和骨架模板生成 Java 代码，映射表没有的信息一律写 "// TODO 人工补充"，禁止编造任何 URL、路径、枚举值。
            2. 保留骨架中全部"人工复核声明"与"TODO 人工实现"注释。
            3. 只输出一个完整的 Java 源文件内容，不要输出任何解释、Markdown 代码块标记或多余文本。
            """;

    private final ChatClient chatClient;
    private final String model;

    public OpenAiCompatibleDraftGeneratorStrategy(ChatClient.Builder builder,
                                                  @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.chatClient = builder.build();
        this.model = model;
    }

    /**
     * 骨架单一来源：classpath 下的 draft/ExampleLenderHandler.java（与 Mock 版读同一份文件）。
     * 注意：这里不能依赖 MockDraftGeneratorStrategy Bean——它只在 rag.draft.provider=mock 时装配，
     * 本类装配时（provider=openai）该 Bean 不存在，构造函数注入会导致启动失败。
     */
    static String loadSkeleton() {
        try (var in = OpenAiCompatibleDraftGeneratorStrategy.class.getResourceAsStream("/draft/ExampleLenderHandler.java")) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少 draft/ExampleLenderHandler.java 骨架");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("骨架模板读取失败", e);
        }
    }

    @Override
    public String generateDraft(String docId, List<FieldMapping> mappings) {
        StringBuilder user = new StringBuilder();
        user.append("【骨架模板（在其上填充）】\n")
            .append(loadSkeleton())
            .append("\n\n【字段映射表】\n");
        for (FieldMapping m : mappings) {
            user.append("- ").append(m.internalFieldId()).append(" (").append(m.internalFieldName())
                .append(") = ").append(m.lenderValue())
                .append("　出处: ").append(m.sourceQuote()).append('\n');
        }
        user.append("\n【文档标识】").append(docId)
            .append("\n请生成 ").append(docId).append(" 的适配层代码草稿。");
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(user.toString())
                .call()
                .content();
    }

    @Override
    public String name() {
        return model;
    }
}
