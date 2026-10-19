package com.lender.assistant.extract.controller;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.extract.service.ExtractService;
import com.lender.assistant.extract.service.InternalFieldCatalog;
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
 * 错误处理：不再就地 catch 手拼错误体——业务失败抛 {@link BizException}，其余异常交给全局异常处理器，
 * 统一转成 code/msg（§5.3）；成功返回体由 ApiResponseBodyAdvice 统一包成 code/msg/data。
 */
@RestController
@RequestMapping("/api/extract")
public class ExtractController {

    private final ExtractService extractService;

    public ExtractController(ExtractService extractService) {
        this.extractService = extractService;
    }

    @PostMapping("/{docId}")
    public Map<String, Object> extract(@PathVariable String docId) {
        // 只调一次模型：结果先落库再转响应，三处复用同一份结果。
        // 文档不存在等异常不再就地 catch——交由全局异常处理器按语义转 400/500
        var mappings = extractService.extractFromDoc(docId);
        extractService.saveMappings(docId, mappings);
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
        return Map.of("docId", docId, "count", rows.size(), "mappings", rows);
    }

    @GetMapping("/{docId}")
    public Map<String, Object> list(@PathVariable String docId) {
        return Map.of("docId", docId, "mappings", extractService.listMappings(docId));
    }

    public record ConfirmRequest(String internalFieldId, String lenderFieldName, String lenderValue) {}

    @PostMapping("/{docId}/confirm")
    public Map<String, Object> confirm(@PathVariable String docId,
                                       @RequestBody ConfirmRequest req) {
        if (req.internalFieldId() == null || req.internalFieldId().isBlank()) {
            // 入参校验失败属业务失败：抛 BizException，由全局处理器转 400 + code/msg
            throw new BizException(ErrorCode.PARAM_INVALID, "internalFieldId 必填");
        }
        extractService.confirm(docId, req.internalFieldId(), req.lenderFieldName(), req.lenderValue());
        return Map.of("ok", true);
    }

    @GetMapping("/fields")
    public List<Map<String, Object>> fields() {
        return InternalFieldCatalog.ALL.stream()
                .map(f -> Map.<String, Object>of("id", f.id(), "name", f.name(), "description", f.description()))
                .toList();
    }
}
