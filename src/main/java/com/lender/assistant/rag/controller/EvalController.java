package com.lender.assistant.rag.controller;

import com.lender.assistant.common.constant.SearchConstants;
import com.lender.assistant.common.constant.SearchMode;
import com.lender.assistant.common.util.TopKUtils;
import com.lender.assistant.rag.service.EvalService;
import com.lender.assistant.rag.vo.request.EvalRequest;
import com.lender.assistant.rag.vo.response.EvalRunResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评估接口：POST /api/admin/eval/run {topK, mode}
 *
 * mode 取值 vector / keyword / hybrid（见 SearchService.search）。
 * 返回 JSON 含指标汇总、逐条结果、markdown 报告全文——
 * 调优实验的固定动作：改参数 → 重跑本接口 → diff 前后 markdown。
 */
@RestController
@RequestMapping("/api/admin/eval")
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    @PostMapping("/run")
    public EvalRunResponse run(@RequestBody EvalRequest request) throws Exception {
        SearchMode mode = SearchMode.fromCode(request.mode());
        return evalService.run(TopKUtils.resolve(request.topK(), SearchConstants.DEFAULT_TOP_K), mode);
    }
}
