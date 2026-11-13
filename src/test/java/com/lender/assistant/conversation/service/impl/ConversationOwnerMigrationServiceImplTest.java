package com.lender.assistant.conversation.service.impl;

import com.lender.assistant.conversation.mapper.ConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 历史会话归属迁移契约：正常迁移、重复执行、管理员未就绪三条路径。
 *
 * 幂等由 SQL 的 {@code WHERE user_id = 来源} 保证：第二次执行天然命中 0 行，
 * 不会把已经改到目标账户的会话再次搬动。
 */
class ConversationOwnerMigrationServiceImplTest {

    private static final String ADMIN_USER_ID = "admin-user-id";

    private ConversationMapper conversationMapper;
    private ConversationOwnerMigrationServiceImpl service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        service = new ConversationOwnerMigrationServiceImpl(conversationMapper);
    }

    @Test
    @DisplayName("管理员就绪：把全部历史调试身份会话归属改为管理员")
    void migratesLegacySessionsToAdmin() {
        when(conversationMapper.migrateOwner(
                ConversationOwnerMigrationServiceImpl.LEGACY_DEBUG_USER_ID, ADMIN_USER_ID))
                .thenReturn(3);

        int migrated = service.migrateLegacyOwner(ADMIN_USER_ID);

        assertEquals(3, migrated, "返回值应是迁移行数");
        verify(conversationMapper).migrateOwner(
                ConversationOwnerMigrationServiceImpl.LEGACY_DEBUG_USER_ID, ADMIN_USER_ID);
    }

    @Test
    @DisplayName("重复执行：第二次命中 0 行且参数不变，不产生额外变更")
    void repeatedMigrationIsIdempotent() {
        when(conversationMapper.migrateOwner(
                ConversationOwnerMigrationServiceImpl.LEGACY_DEBUG_USER_ID, ADMIN_USER_ID))
                .thenReturn(2, 0);

        int first = service.migrateLegacyOwner(ADMIN_USER_ID);
        int second = service.migrateLegacyOwner(ADMIN_USER_ID);

        assertEquals(2, first, "首次应迁移已有的 2 条会话");
        assertEquals(0, second, "再次执行应命中 0 行");
        verify(conversationMapper, times(2)).migrateOwner(
                ConversationOwnerMigrationServiceImpl.LEGACY_DEBUG_USER_ID, ADMIN_USER_ID);
    }

    @Test
    @DisplayName("初始管理员未就绪：跳过迁移且不访问数据库")
    void skipsWhenAdminIsNotReady() {
        int migrated = service.migrateLegacyOwner(null);

        assertEquals(0, migrated, "跳过时返回 0");
        assertEquals(0, service.migrateLegacyOwner("   "), "空白标识同样视为未就绪");
        verifyNoInteractions(conversationMapper);
    }
}
