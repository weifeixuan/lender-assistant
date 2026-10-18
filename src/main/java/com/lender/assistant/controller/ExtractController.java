package com.lender.assistant.controller;

import com.lender.assistant.extract.FieldMappingStore;
import com.lender.assistant.extract.service.ExtractService;
import com.lender.assistant.extract.service.InternalFieldCatalog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化抽取 REST 接口（阶段4）：
 * - POST /api/extract/{docId}          对已入库文档跑抽取，结果落库并返回映射表
 * - GET  /api/extract/{docId}          查某文档当前映射表（含 PENDING/CONFIRMED 状态）
 * - POST /api/extract/{docId}/confirm  人工确认/修正一条映射
 * - GET  /api/extract/fields          内部标准字段目录（前端渲染映射表表头用）
 *
 * 错误处理：文档不存在 → 400；模型/链路故障 → 500 + 可读信息（降级不裸奔）。
 */
@RestController
@RequestMapping("/api/extract")
public class ExtractController {

    private final ExtractService extractService;
    private final FieldMappingStore store;

    public ExtractController(ExtractService extractService, FieldMappingStore store) {
        this.extractService = extractService;
        this.store = store;
    }

    @PostMapping("/{docId}")
    public ResponseEntity<Map<String, Object>> extract(@PathVariable String docId) {
        try {
            // 只调一次模型：结果先落库再转响应，三处复用同一份结果
            var mappings = extractService.extractFromDoc(docId);
            store.saveMappings(docId, mappings);
            List<Map<String, Object>> rows = mappings.stream()
                    .map(m -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("internalFieldId", m.internalFieldId());
                        row.put("internalFieldName", m.internalFieldName());
                        row.put("lenderFieldName", m.lenderFieldName());
                        row.put("lenderValue", m.lenderValue());
                        row.put("sourceQuote", m.sourceQuote());
                        row.put("confidence", m.confidence());
                        return row;
                    })
                    .toList();
            return ResponseEntity.ok(Map.of("docId", docId, "count", rows.size(), "mappings", rows));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "抽取链路故障（已留痕），可稍后重试或转人工: " + e.getMessage()));
        }
    }

    @GetMapping("/{docId}")
    public Map<String, Object> list(@PathVariable String docId) {
        return Map.of("docId", docId, "mappings", store.listMappings(docId));
    }

    public record ConfirmRequest(String internalFieldId, String lenderFieldName, String lenderValue) {}

    @PostMapping("/{docId}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable String docId,
                                                       @RequestBody ConfirmRequest req) {
        if (req.internalFieldId() == null || req.internalFieldId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "internalFieldId 必填"));
        }
        store.confirm(docId, req.internalFieldId(), req.lenderFieldName(), req.lenderValue());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/fields")
    public List<Map<String, Object>> fields() {
        return InternalFieldCatalog.ALL.stream()
                .map(f -> Map.<String, Object>of("id", f.id(), "name", f.name(), "description", f.description()))
                .toList();
    }
}
