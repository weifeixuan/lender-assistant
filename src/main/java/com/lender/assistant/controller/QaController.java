package com.lender.assistant.controller;

import com.lender.assistant.rag.qa.QaService;
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
        int topK = body.get("topK") == null ? 4 : (Integer) body.get("topK");
        return qaService.answer(question.trim(), topK);
    }
}
