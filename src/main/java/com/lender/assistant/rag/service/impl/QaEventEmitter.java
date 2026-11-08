package com.lender.assistant.rag.service.impl;

import com.lender.assistant.common.constant.MessageConstants;
import com.lender.assistant.common.util.ExceptionMessageUtils;
import com.lender.assistant.conversation.model.dto.QaSourceSnapshotDTO;
import com.lender.assistant.rag.model.response.QaDoneResponse;
import com.lender.assistant.rag.model.response.QaSourceResponse;
import com.lender.assistant.rag.model.response.SearchItemResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * `/api/qa` 的 SSE 事件发射：决定事件语义、载荷与失败后的状态收尾。
 *
 * <p>无状态协作类，由 {@code QaServiceImpl} 内部 {@code new}；{@code send} 为包级私有，
 * 供同包的编排代码在原地的 meta/route/plan/step/done 处复用。</p>
 */
final class QaEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(QaEventEmitter.class);

    /** @return false 表示客户端已断开或已置位断开标志（调用方无需重复处理） */
    boolean send(SseEmitter emitter, String event, Object data, RoundStateServiceImpl state) {
        if (state.isDisconnected()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IOException e) {
            // 客户端主动断开 / 管道关闭：正常的请求终止，DEBUG 留痕即可，不刷 WARN
            log.debug("SSE 事件发送失败，客户端已断开: event={}, reason={}", event, e.getMessage());
            state.disconnected();
            return false;
        } catch (RuntimeException e) {
            // 序列化失败或 emitter 已 completed：属于代码/状态异常，必须 WARN 带堆栈，不能静默丢事件
            log.warn("SSE 事件发送失败（非客户端断开）: event={}", event, e);
            state.disconnected();
            return false;
        }
    }

    /** 召回结果 → sources 事件；返回 false 表示客户端已断开（disconnected 已落 STOPPED） */
    boolean sources(SseEmitter emitter, List<SearchItemResponse> hits, RoundStateServiceImpl state) {
        List<QaSourceResponse> sources = hits.isEmpty() ? List.of() : buildSources(hits);
        // 落库用 conversation 包内的快照类型（JSON 同构），避免 conversation 反向依赖 rag 的响应 VO
        state.sources(sources.stream().map(QaEventEmitter::toSnapshot).toList());
        // 来源列表先发；未召回时也发空数组，前端协议保持单一形态
        return send(emitter, "sources", sources, state);
    }

    /** 无关问题：固定提示不检索不生成，sources 为空，provider=rule */
    void irrelevant(SseEmitter emitter, String reply, RoundStateServiceImpl state) {
        state.sources(List.of());
        if (!send(emitter, "sources", List.of(), state)) {
            return;
        }
        state.append(reply);
        if (!send(emitter, "delta", reply, state)) {
            return;
        }
        state.provider(MessageConstants.RULE_PROVIDER);
        state.done();
        if (!send(emitter, "done",
                new QaDoneResponse(MessageConstants.RULE_PROVIDER, state.elapsedMillis(), 0), state)) {
            return;
        }
        emitter.complete();
    }

    /**
     * 无召回分支：引导文案不是模型产出，provider 恒为 rule。
     * 终态先落库再发 done——done 只是把已完成的事实告知前端，落库失败不应被当成"已完成"。
     */
    void noRecall(SseEmitter emitter, RoundStateServiceImpl state) {
        String guidance = "资料库中没有检索到相关内容，请先在上方上传并入库文档。";
        state.append(guidance);
        if (!send(emitter, "delta", guidance, state)) {
            return;
        }
        state.provider(MessageConstants.RULE_PROVIDER);
        state.done();
        if (!send(emitter, "done",
                new QaDoneResponse(MessageConstants.RULE_PROVIDER, state.elapsedMillis(), 0), state)) {
            return;
        }
        emitter.complete();
    }

    /** 熔断打开：推送降级提示后断流，终态 FAILED；发送失败说明客户端已断开，不再重复收尾 */
    void circuitOpen(SseEmitter emitter, CallNotPermittedException e, RoundStateServiceImpl state) {
        // 熔断打开：连续失败触发保护，快速失败。降级而非崩溃（手册验收线）——
        // sources 已经推给前端，检索能力不受模型故障影响。
        // 记 WARN 不记 ERROR：这是熔断器的正常降级动作，但需有人从日志看到"模型在连续失败"
        log.warn("问答链路熔断打开，已降级为可读提示: {}", e.getMessage());
        boolean sent = send(emitter, "error", "模型服务熔断中（连续失败已触发保护），已转人工流程兜底；"
                + "上方召回来源仍可查看。模型恢复前请使用检索测试区块。", state);
        state.failed("模型服务熔断中");
        if (sent) {
            // 已按协议发出 error 事件：正常结束 SSE（响应已提交，completeWithError 只会再抛一次异步错误）
            emitter.complete();
        }
    }

    /** 通用异常收尾：推送 error 事件后断流，保留已生成正文并按 FAILED 落库 */
    void failure(SseEmitter emitter, String question, Exception e, RoundStateServiceImpl state) {
        // 模型超时/网络异常/DB 异常统一走 error 事件——前端能拿到可读信息而非静默断流。
        // 必须带堆栈落 ERROR：SSE 已断流，这是排查链路上唯一的完整证据
        log.error("问答链路故障，已推送 error 事件并断流: question={}", question, e);
        // 不能直接拼 e.getMessage()：框架包装异常可能返回 null，用户会看到「问答失败：null」，
        // 落库的 error 字段同样失去意义——统一走 describe 取根因/类名兜底
        String reason = "问答失败：" + ExceptionMessageUtils.describe(e);
        boolean sent = send(emitter, "error", reason, state);
        state.failed(reason);
        if (sent) {
            // error 事件已经让前端拿到失败语义：此处正常 complete，不再 completeWithError
            // （响应已提交，completeWithError 会触发容器级错误回调，日志里多一条无意义的异常）
            emitter.complete();
        }
    }

    /** SSE 响应元素 → 落库快照，字段逐项对应（QaSourceSnapshotDTO 与 QaSourceResponse 同构） */
    private static QaSourceSnapshotDTO toSnapshot(QaSourceResponse source) {
        return new QaSourceSnapshotDTO(source.idx(), source.docTitle(), source.sectionPath(),
                source.chunkId(), source.content(), source.vectorDistance(),
                source.keywordHits(), source.bothChannels(), source.rrfScore());
    }

    /** 召回结果 → 前端来源列表（idx 从 1 起，与【来源N】编号一致） */
    private static List<QaSourceResponse> buildSources(List<SearchItemResponse> hits) {
        List<QaSourceResponse> sources = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            SearchItemResponse hit = hits.get(i);
            sources.add(new QaSourceResponse(i + 1, hit.docTitle(), hit.sectionPath(), hit.chunkId(),
                    hit.content(), hit.vectorDistance(), hit.keywordHits(), hit.bothChannels(), hit.rrfScore()));
        }
        return sources;
    }
}
