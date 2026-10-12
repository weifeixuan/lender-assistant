package com.lender.assistant.controller;

import com.lender.assistant.rag.eval.EvalService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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
    public Map<String, Object> run(@RequestBody Map<String, Object> body) throws Exception {
        int topK = body.get("topK") == null ? 4 : (Integer) body.get("topK");
        String mode = body.get("mode") == null ? "hybrid" : (String) body.get("mode");
        return evalService.run(topK, mode);
    }
}
