package com.lender.assistant.draft.controller;

import com.lender.assistant.draft.service.DraftService;
import com.lender.assistant.draft.vo.response.DraftResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 代码草稿接口（阶段5）：映射表 → Java 适配层草稿。
 *
 * 响应固定携带 reviewNotice（人工复核声明）——前端据此渲染醒目提示条，
 * 这是"AI 代码必须人工复核"红线在 API 契约层的固化。
 *
 * 错误处理：不再就地 catch 手拼错误体——异常交给全局处理器统一转 code/msg，
 * 成功返回体由 ResponseWrapAspect 包成 code/msg/data（§5.3）。
 *
 * 响应直接用 {@link DraftResponse}：其组件名与旧手拼 Map 的键名逐字一致，
 * 无需再包一层——契约仍由接口类型固定。
 */
@RestController
@RequestMapping("/api/draft")
public class DraftController {

    private final DraftService draftService;

    public DraftController(DraftService draftService) {
        this.draftService = draftService;
    }

    @PostMapping("/{docId}")
    public DraftResponse generate(@PathVariable String docId) {
        return draftService.generate(docId);
    }
}
