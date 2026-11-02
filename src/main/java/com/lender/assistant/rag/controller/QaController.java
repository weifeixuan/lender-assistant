package com.lender.assistant.rag.controller;

import com.lender.assistant.common.annotation.RawResponse;
import com.lender.assistant.common.constant.SearchConstants;
import com.lender.assistant.common.exception.BizException;
import com.lender.assistant.common.exception.ErrorCode;
import com.lender.assistant.common.util.TopKUtils;
import com.lender.assistant.common.web.UserIdInterceptor;
import com.lender.assistant.rag.vo.request.QaRequest;
import com.lender.assistant.rag.service.QaService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.regex.Pattern;

/**
 * 问答接口（阶段 2）：POST /api/qa，SSE 流式返回。
 *
 * 为什么用 POST 而不是 GET：原生 EventSource 只支持 GET，
 * 但问题文本放 URL 不合适（长、且会进访问日志）——
 * 前端用 fetch POST + ReadableStream 手工解析 SSE 报文（见 index.html），
 * 拿回"URL 干净 + 请求体传参 + 流式渲染"三者兼得。
 *
 * @RawResponse：SSE 是原始流式响应，必须跳过统一响应体包装（包一层 {code,msg,data} 会破坏事件协议）。
 * 注意 AOP 日志切面仍会记录本接口的进入/返回（切面按其自身切点匹配，不受此注解影响）。
 */
@RestController
@RawResponse
public class QaController {

    /** 会话 id 由后端 UUID 生成；客户端回传非法格式属入参错误，不进 SSE 流 */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }

    @PostMapping(value = "/api/qa", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter qa(@RequestBody QaRequest request,
                         @RequestAttribute(UserIdInterceptor.USER_ID_ATTRIBUTE) String userId) {
        String question = request.question();
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        String conversationId = request.conversationId();
        if (conversationId != null && !UUID_PATTERN.matcher(conversationId).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "conversationId 必须是 UUID");
        }
        return qaService.answer(question.trim(),
                TopKUtils.resolve(request.topK(), SearchConstants.DEFAULT_TOP_K), conversationId, userId);
    }
}
