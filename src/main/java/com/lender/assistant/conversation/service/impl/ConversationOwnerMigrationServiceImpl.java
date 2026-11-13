package com.lender.assistant.conversation.service.impl;

import com.lender.assistant.conversation.mapper.ConversationMapper;
import com.lender.assistant.conversation.service.ConversationOwnerMigrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 历史会话归属迁移实现：判定「管理员是否就绪」后交给一条带来源条件的 UPDATE。
 *
 * 不迁移的消息行：message 表没有 user_id，可见性完全由会话归属推导，改会话即可。
 */
@Service
public class ConversationOwnerMigrationServiceImpl implements ConversationOwnerMigrationService {

    private static final Logger log =
            LoggerFactory.getLogger(ConversationOwnerMigrationServiceImpl.class);

    /** 登录体系上线前的固定调试身份 */
    static final String LEGACY_DEBUG_USER_ID = "dev-user-001";

    private final ConversationMapper conversationMapper;

    public ConversationOwnerMigrationServiceImpl(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int migrateLegacyOwner(String bootstrapAdminUserId) {
        if (bootstrapAdminUserId == null || bootstrapAdminUserId.isBlank()) {
            // 缺口令时自举会跳过建号：迁移整段跳过，只留一条警告，不影响启动
            log.warn("初始管理员未就绪，跳过历史会话归属迁移（来源账户 {}）", LEGACY_DEBUG_USER_ID);
            return 0;
        }
        int migrated = conversationMapper.migrateOwner(
                LEGACY_DEBUG_USER_ID, bootstrapAdminUserId);
        log.info("历史会话归属迁移完成：{} -> {}，迁移 {} 条",
                LEGACY_DEBUG_USER_ID, bootstrapAdminUserId, migrated);
        return migrated;
    }
}
