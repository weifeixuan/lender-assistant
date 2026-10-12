package com.lender.assistant.draft;

import com.lender.assistant.extract.FieldMapping;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DeepSeek 真实草稿生成：把骨架与映射表交给模型，产出适配层 Java 草稿。
 *
 * Prompt 铁律（呼应 QaService 三铁律的"宁缺勿编"）：
 * - 只依据映射表与骨架生成，映射表没有的信息留 TODO，禁止编造
 * - 只输出 Java 源码（.call().content() 非流式——草稿是整篇产物，无流式需求）
 *
 * key 缺失不装配（starter 的 api-key 占位在 chat 侧已处理；本 Bean 仅在
 * rag.draft.provider=deepseek 时创建，Mock 为默认）。
 */
@Component
@ConditionalOnProperty(name = "rag.draft.provider", havingValue = "deepseek")
public class DeepSeekDraftGenerator implements DraftGenerator {

    private static final String SYSTEM_PROMPT = """
            你是融资租赁资方接入适配层的代码生成助手。规则：
            1. 只依据给出的字段映射表和骨架模板生成 Java 代码，映射表没有的信息一律写 "// TODO 人工补充"，禁止编造任何 URL、路径、枚举值。
            2. 保留骨架中全部"人工复核声明"与"TODO 人工实现"注释。
            3. 只输出一个完整的 Java 源文件内容，不要输出任何解释、Markdown 代码块标记或多余文本。
            """;

    private final ChatClient chatClient;
    private final MockDraftGenerator skeletonSource;

    public DeepSeekDraftGenerator(ChatClient.Builder builder, MockDraftGenerator skeletonSource) {
        this.chatClient = builder.build();
        // 骨架文本来源复用 Mock 的加载逻辑（单一来源：classpath draft/）
        this.skeletonSource = skeletonSource;
    }

    @Override
    public String generateDraft(String docId, List<FieldMapping> mappings) {
        StringBuilder user = new StringBuilder();
        user.append("【骨架模板（在其上填充）】\n")
            .append(skeletonSource.skeletonText())
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
        return "DeepSeek";
    }
}
