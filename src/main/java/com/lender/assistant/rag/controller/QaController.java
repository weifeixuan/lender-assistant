package com.lender.assistant.rag.controller;

import com.lender.assistant.rag.service.QaService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 问答接口（阶段 2）：POST /api/qa，SSE 流式返回。
 *
 * 为什么用 POST 而不是 GET：原生 EventSource 只支持 GET，
 * 但问题文本放 URL 不合适（长、且会进访问日志）——
 * 前端用 fetch POST + ReadableStream 手工解析 SSE 报文（见 index.html），
 * 拿回"URL 干净 + 请求体传参 + 流式渲染"三者兼得。
 */
@RestController
public class QaController {

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }

    @PostMapping(value = "/api/qa", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter qa(@RequestBody Map<String, Object> body) {
        String question = (String) body.get("question");
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        return qaService.answer(question.trim(), topK(body.get("topK"), 4));
    }

    /** topK 统一校验：非整数或非正数直接拒绝，避免 ClassCastException / subList 越界 */
    private static int topK(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number n)) {
            throw new IllegalArgumentException("topK 必须是整数");
        }
        int v = n.intValue();
        if (v <= 0) {
            throw new IllegalArgumentException("topK 必须为正整数");
        }
        return v;
    }
}
