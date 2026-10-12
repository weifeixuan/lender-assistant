-- 阶段4 数据库变更：字段映射表 + 模型调用留痕表
-- 在 lender_assistant 库执行（容器内: psql -U postgres -d lender_assistant -f ...）

-- 字段映射表：抽取结果落库 + 人工确认状态机
-- 状态流转: PENDING(模型抽出待确认) -> CONFIRMED(人工确认) / CORRECTED(人工修正过)
CREATE TABLE IF NOT EXISTS field_mapping (
    id                BIGSERIAL PRIMARY KEY,
    doc_id            TEXT NOT NULL,
    internal_field_id TEXT NOT NULL,
    lender_field_name TEXT,
    lender_value      TEXT,
    source_quote      TEXT,
    confidence        NUMERIC(4,3),
    status            TEXT NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (doc_id, internal_field_id)
);

-- 模型调用留痕：每次模型调用一行（耗时/token/成本/状态）
-- token 为 NULL 表示 mock 调用（无真实计量）；cost_cny 默认 0，DeepSeek 版按价目折算
CREATE TABLE IF NOT EXISTS llm_call_log (
    id                BIGSERIAL PRIMARY KEY,
    scene             TEXT NOT NULL,              -- qa / extract
    provider          TEXT NOT NULL,              -- Mock / DeepSeek
    duration_ms       INTEGER,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    cost_cny          NUMERIC(10,6) NOT NULL DEFAULT 0,
    status            TEXT NOT NULL,              -- OK / FAIL
    error_msg         TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_llm_call_log_created ON llm_call_log (created_at DESC);
