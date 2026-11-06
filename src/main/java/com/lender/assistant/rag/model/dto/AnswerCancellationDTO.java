package com.lender.assistant.rag.model.dto;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次生成的取消句柄：客户端断开时由编排方 {@link #cancel()}，实现方据此停止上游订阅。
 *
 * 为什么是"对象"而不是布尔标志：真实模型实现需要在取消瞬间 dispose Reactor 订阅
 * （Flux 可能正卡在没有新 token 的网络等待里，光靠"下一片 token 到达时再判断"停不下来），
 * 所以要支持注册一个取消动作；Mock 实现则只需在每片 token 之间轮询 {@link #isCancelled()}。
 *
 * 竞态说明：cancel 可能发生在实现方注册动作之前（客户端在 meta 之后就 abort），
 * 此时 {@link #onCancel(Runnable)} 会立即执行动作；动作只执行一次。
 */
public final class AnswerCancellationDTO {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Runnable> action = new AtomicReference<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 注册取消动作；若此时已取消，动作立即执行（至多执行一次） */
    public void onCancel(Runnable cancelAction) {
        action.set(cancelAction);
        if (cancelled.get()) {
            fire();
        }
    }

    /** 编排方在 SSE 断开/超时/发送失败时调用；幂等 */
    public void cancel() {
        cancelled.set(true);
        fire();
    }

    private void fire() {
        Runnable pending = action.getAndSet(null);
        if (pending != null) {
            pending.run();
        }
    }
}
