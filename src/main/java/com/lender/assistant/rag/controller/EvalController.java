package com.lender.assistant.rag.controller;

import com.lender.assistant.common.constant.SearchConstants;
import com.lender.assistant.common.constant.SearchMode;
import com.lender.assistant.common.util.TopKUtils;
import com.lender.assistant.rag.service.EvalService;
import com.lender.assistant.rag.service.RoutingEvalService;
import com.lender.assistant.rag.model.request.EvalRequest;
import com.lender.assistant.rag.model.response.EvalRunResponse;
import com.lender.assistant.rag.model.response.RoutingEvalResponse;
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
    private final RoutingEvalService routingEvalService;

    public EvalController(EvalService evalService, RoutingEvalService routingEvalService) {
        this.evalService = evalService;
        this.routingEvalService = routingEvalService;
    }

    @PostMapping("/run")
    public EvalRunResponse run(@RequestBody EvalRequest request) throws Exception {
        SearchMode mode = SearchMode.fromCode(request.mode());
        return evalService.run(TopKUtils.resolve(request.topK(), SearchConstants.DEFAULT_TOP_K), mode);
    }

    /**
     * 路由与计划离线评估：POST /api/admin/eval/routing（无入参，跑固定评估集）。
     * 只输出当前阈值下的指标，不做阈值调优。
     */
    @PostMapping("/routing")
    public RoutingEvalResponse runRouting() throws Exception {
        return routingEvalService.run();
    }
}
