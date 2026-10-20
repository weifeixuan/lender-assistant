package com.lender.assistant.extract.controller;

import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.extract.model.InternalField;
import com.lender.assistant.extract.service.ExtractService;
import com.lender.assistant.extract.service.InternalFieldCatalog;
import com.lender.assistant.extract.vo.request.ConfirmRequest;
import com.lender.assistant.extract.vo.response.ConfirmResponse;
import com.lender.assistant.extract.vo.response.ExtractListResponse;
import com.lender.assistant.extract.vo.response.ExtractRunResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public ExtractRunResponse extract(@PathVariable String docId) {
        // 只调一次模型：结果先落库再转响应，三处复用同一份结果。
        // 文档不存在等异常不再就地 catch——交由全局异常处理器按语义转 400/500
        var mappings = extractService.extractFromDoc(docId);
        extractService.saveMappings(docId, mappings);
        return new ExtractRunResponse(docId, mappings.size(), mappings);
    }

    @GetMapping("/{docId}")
    public ExtractListResponse list(@PathVariable String docId) {
        return new ExtractListResponse(docId, extractService.listMappings(docId));
    }

    @PostMapping("/{docId}/confirm")
    public ConfirmResponse confirm(@PathVariable String docId,
                                 @RequestBody ConfirmRequest req) {
        if (req.internalFieldId() == null || req.internalFieldId().isBlank()) {
            // 入参校验失败属业务失败：抛 BizException，由全局处理器转 400 + code/msg
            throw new BizException(ErrorCode.PARAM_INVALID, "internalFieldId 必填");
        }
        extractService.confirm(docId, req.internalFieldId(), req.lenderFieldName(), req.lenderValue());
        return new ConfirmResponse(true);
    }

    @GetMapping("/fields")
    public List<InternalField> fields() {
        return InternalFieldCatalog.ALL;
    }
}
