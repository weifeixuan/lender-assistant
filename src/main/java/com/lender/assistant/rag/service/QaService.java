package com.lender.assistant.rag.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答编排（检索 → 拼 Prompt → 模型生成 → SSE 流式）。
 * 抽接口的理由：SSE 契约（事件名 sources/delta/done/error）是对外协议，
 * 前端按它分发；实现细节（线程池、Prompt 文案）不该被调用方感知。
 */
public interface QaService {

    /** 立即返回 emitter，检索/生成/send 在独立工作线程执行（真流式的必要条件） */
    SseEmitter answer(String question, int topK);
}
