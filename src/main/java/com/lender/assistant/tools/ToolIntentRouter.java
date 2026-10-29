package com.lender.assistant.tools;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 工具意图路由（Mock 模式的"模型决策"）。
 *
 * 诚实声明：真实 Function Calling 的决策由模型做（真实模型版走 Spring AI .tools()，
 * 模型看工具 description 自己决定调不调）；这里用关键词规则模拟这个决策，
 * 让"问题 → 决策 → 调工具 → 结果进回答"的链路在无 key 时可开发可测试。
 * 与 HashEmbeddingStrategy 同一定位：验证链路对不对，不评估决策效果好不好。
 *
 * 规则设计原则：参数拼不出来就宁可不调（如问 chunk 数但认不出 docId），
 * 错误参数的调用比不调用更糟——这也是给真实模型定 prompt 的原则。
 */
@Component
public class ToolIntentRouter {

    public record ToolIntent(String toolName, String argument, String reason) {}

    /**
     * knownDocIds 用 Supplier 惰性求值：只有统计意图才需要 docId 清单，
     * 普通问题零额外查询（列表意图靠关键词即可判定）。
     */
    public Optional<ToolIntent> route(String question, java.util.function.Supplier<List<String>> knownDocIds) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String q = question.toLowerCase();

        // 统计意图优先于列表意图（更具体）：含 chunk/块 且能定位到 docId
        boolean asksStats = q.contains("chunk") || q.contains("多少块") || q.contains("几块")
                || q.contains("切块") || q.contains("切了几");
        if (asksStats) {
            Optional<String> docId = knownDocIds.get().stream().filter(q::contains).findFirst();
            if (docId.isPresent()) {
                return Optional.of(new ToolIntent("getDocChunkStats", docId.get(),
                        "问题询问某文档的切块数量，且问题中包含已入库的 docId"));
            }
            return Optional.empty(); // 认不出 docId：错误参数的调用比不调用更糟
        }

        // 列表意图
        boolean asksList = q.contains("已入库") || q.contains("哪些文件") || q.contains("文件列表")
                || q.contains("哪些文档") || q.contains("上传了") || q.contains("入库文件");
        if (asksList) {
            return Optional.of(new ToolIntent("listIngestedDocs", null, "问题询问已入库文件清单"));
        }
        return Optional.empty();
    }
}
