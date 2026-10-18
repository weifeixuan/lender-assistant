package com.lender.assistant.tools;

import com.lender.assistant.rag.model.DocSummary;
import com.lender.assistant.rag.service.DocumentService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Function Calling 的业务查询工具（手册阶段4：模型可调用 1-2 个业务查询接口）。
 *
 * 两个工具都直查 lender-pg 真实数据——工具返回的是真东西，
 * "模型自主决定调用"这层才值得验证。
 *
 * @Tool 注解由 Spring AI 扫描：DeepSeek 版把本类对象挂到 ChatClient.tools()，
 * 模型按 description 自主决定何时调用（真 Function Calling）；
 * Mock 版由 ToolIntentRouter 规则模拟这个决策（见该类诚实声明）。
 */
@Component
public class LenderQueryTools {

    private final DocumentService documentService;

    public LenderQueryTools(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Tool(name = "listIngestedDocs", description = "查询已入库的资方文件列表，返回每个文档的 docId、标题与 chunk 数量")
    public String listIngestedDocs() {
        List<DocSummary> docs = documentService.listDocuments();
        if (docs.isEmpty()) {
            return "资料库中暂无已入库文档。";
        }
        StringBuilder sb = new StringBuilder("已入库文档共 ").append(docs.size()).append(" 份：\n");
        for (int i = 0; i < docs.size(); i++) {
            DocSummary d = docs.get(i);
            sb.append(i + 1).append(". [").append(d.docId()).append("] ")
              .append(d.docTitle()).append("（").append(d.chunkCount()).append(" 块）\n");
        }
        return sb.toString().trim();
    }

    @Tool(name = "getDocChunkStats", description = "查询指定 docId 文档的入库 chunk 数量")
    public String getDocChunkStats(@ToolParam(description = "文档 docId") String docId) {
        int n = documentService.countByDoc(docId);
        return n == 0 ? docId + " 未入库或无内容。" : docId + " 已入库 " + n + " 个 chunk。";
    }

    /** 供 ToolIntentRouter 做 docId 匹配（非工具方法，不暴露给模型） */
    public List<String> knownDocIds() {
        return documentService.listDocuments().stream().map(DocSummary::docId).toList();
    }
}
