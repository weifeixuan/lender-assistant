package com.lender.assistant.extract.strategy.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lender.assistant.extract.model.entity.FieldMappingEntity;
import com.lender.assistant.extract.model.entity.InternalFieldEntity;
import com.lender.assistant.extract.service.InternalFieldCatalog;
import com.lender.assistant.extract.strategy.ExtractGeneratorStrategy;
import com.lender.assistant.rag.model.dto.DocContentDTO;
import com.lender.assistant.resilience.LlmCallLogger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实模型抽取：JSON Schema 抽取，非流式一锤子调用（OpenAI 兼容协议）。
 *
 * 与 Mock 版同一条红线——宁缺勿编：
 * - Prompt 明确要求"文档没写的信息不得输出"；
 * - 解析阶段再按 InternalFieldCatalog 过滤：未知 internalFieldId 一律丢弃，
 *   模型幻觉出的字段进不了映射表；
 * - 空值/空串字段同样丢弃——映射表里宁可没有，不可有假值。
 *
 * 韧性（重试/熔断/留痕）不在此类：由 ExtractService 经 ResilientLlmExecutor 统一处理，
 * 与 Mock 版共享同一编排（allowRetry=true，抽取是非流式可安全重试）。
 */
@Component
@ConditionalOnProperty(name = "rag.extract.provider", havingValue = "openai")
public class OpenAiCompatibleExtractGeneratorStrategy implements ExtractGeneratorStrategy {

    private static final double DEFAULT_CONFIDENCE = 0.8;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final LlmCallLogger llmCallLogger;
    private final String model;

    public OpenAiCompatibleExtractGeneratorStrategy(ChatClient.Builder builder,
                                                    ObjectMapper objectMapper,
                                                    LlmCallLogger llmCallLogger,
                                                    @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
        this.llmCallLogger = llmCallLogger;
        this.model = model;
    }

    @Override
    public List<FieldMappingEntity> extract(DocContentDTO doc) {
        ChatResponse response = chatClient.prompt()
                .system(systemPrompt())
                .user(userPrompt(doc.docTitle(), doc.text()))
                .call()
                .chatResponse();
        // 非流式响应带 Usage：回填给外层 record() 落库；取不到则留 NULL
        llmCallLogger.captureUsage(response);
        return parse(response.getResult().getOutput().getText());
    }

    private String systemPrompt() {
        StringBuilder sb = new StringBuilder("""
                你是融资租赁资方接入文档的结构化抽取引擎。任务：从资方文档中抽取"内部标准字段"的对应信息，输出 JSON 数组。

                铁律：
                1. 只抽取文档中真实存在的信息；文档没有写的字段，一律不要出现在结果里（宁缺勿编）。
                2. 不得推测、不得补全、不得编造任何 URL、路径、枚举值。
                3. 每条记录必须给出 sourceQuote —— 该值在文档中的原文出处片段，供人工回查。
                4. 只输出 JSON 数组本身，不要输出解释、Markdown 代码块标记或任何多余文本。

                内部标准字段清单（internalFieldId 必须取自以下之一）：
                """);
        for (InternalFieldEntity f : InternalFieldCatalog.ALL) {
            sb.append("- ").append(f.id()).append("（").append(f.name()).append("）：")
              .append(f.description()).append('\n');
        }
        sb.append("""

                输出格式（数组元素）：
                [{"internalFieldId":"lender_name","lenderFieldName":"资方文档里的标签","lenderValue":"抽取到的值","sourceQuote":"原文出处片段","confidence":0.9}]
                """);
        return sb.toString();
    }

    private String userPrompt(String docTitle, String docText) {
        return "【文档标题】" + (docTitle == null ? "" : docTitle)
                + "\n\n【文档全文】\n" + docText
                + "\n\n请抽取上述文档中的内部标准字段，输出 JSON 数组。";
    }

    /** 解析并过滤模型输出：未知字段 id、空值一律丢弃 */
    private List<FieldMappingEntity> parse(String raw) {
        List<FieldMappingEntity> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String json = extractJsonArray(raw);
        if (json == null) {
            throw new IllegalStateException("模型抽取输出中未找到 JSON 数组");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            // 模型没按 JSON 输出：抛出去，由 ExtractService 的韧性层重试并留痕
            throw new IllegalStateException("模型抽取结果无法解析为 JSON: " + e.getMessage(), e);
        }
        if (!root.isArray()) {
            throw new IllegalStateException("模型抽取输出不是 JSON 数组");
        }
        for (JsonNode n : root) {
            String id = text(n, "internalFieldId");
            String value = text(n, "lenderValue");
            InternalFieldEntity def = id == null ? null : InternalFieldCatalog.byId(id);
            // 未知字段 id 或空值：丢弃（模型幻觉进不了映射表）
            if (def == null || value == null || value.isBlank()) {
                continue;
            }
            String lenderField = text(n, "lenderFieldName");
            String quote = text(n, "sourceQuote");
            out.add(new FieldMappingEntity(id, def.name(),
                    lenderField == null || lenderField.isBlank() ? def.name() : lenderField,
                    value,
                    quote == null ? "" : quote,
                    n.hasNonNull("confidence") ? n.get("confidence").asDouble(DEFAULT_CONFIDENCE)
                            : DEFAULT_CONFIDENCE));
        }
        return out;
    }

    /** 兼容模型偶尔包裹的 ```json ``` 代码块：截取第一个 [ 到最后一个 ] */
    private String extractJsonArray(String raw) {
        String trimmed = raw.trim();
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        return start < 0 || end <= start ? null : trimmed.substring(start, end + 1);
    }

    private String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    @Override
    public String name() {
        return model;
    }
}
