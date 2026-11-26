package com.lender.assistant.common.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地锁组件单测：同键互斥、异键独立、重复释放无副作用。
 */
class LocalLockComponentTest {

    private final LocalLockComponent lock = new LocalLockComponent();

    @Test
    @DisplayName("同键：首次占用成功，占用期间再抢失败，释放后可重新占用")
    void sameKeyIsMutuallyExclusive() {
        assertTrue(lock.tryAcquire("conversation:conv-1"), "首次占用必须成功");
        assertFalse(lock.tryAcquire("conversation:conv-1"), "占用期间同键再抢必须失败");

        lock.release("conversation:conv-1");

        assertFalse(lock.isBusy("conversation:conv-1"), "释放后不再占用");
        assertTrue(lock.tryAcquire("conversation:conv-1"), "释放后可以重新占用");
    }

    @Test
    @DisplayName("异键独立：A 键被占不影响 B 键")
    void differentKeysAreIndependent() {
        assertTrue(lock.tryAcquire("conversation:conv-1"));

        assertFalse(lock.isBusy("conversation:conv-2"), "未占用的键不得报告为忙");
        assertTrue(lock.tryAcquire("conversation:conv-2"), "异键占用互不影响");
    }

    @Test
    @DisplayName("重复释放无副作用：释放未持有的键不抛异常也不影响他人")
    void releaseWithoutHoldingIsNoOp() {
        assertTrue(lock.tryAcquire("conversation:conv-1"));

        assertDoesNotThrow(() -> lock.release("conversation:conv-2"),
                "释放未持有的键必须无副作用");

        assertTrue(lock.isBusy("conversation:conv-1"), "释放他人键不得影响已占用的键");
    }
}
