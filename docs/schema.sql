-- =============================================================
-- 资方接入智能助手：完整数据库结构（lender_assistant 库）
-- -------------------------------------------------------------
-- 本项目全部表结构集中在此文件（由原 schema_phase1.sql + schema_phase4.sql 合并而来）。
-- 所有语句幂等（IF NOT EXISTS），既有库重跑安全。
--
-- 执行方式：
--   手动: docker exec -i lender-pg psql -U postgres -d lender_assistant < docs/schema.sql
--   compose 首次启动: 已挂载到 /docker-entrypoint-initdb.d/01_schema.sql 自动执行
-- =============================================================

-- ========== 扩展 ==========

-- pgvector: 向量类型与相似度算子（<=> 余弦距离）。必须建在 lender_assistant 库——
-- vector 扩展不跨库，落到 postgres 库里本库照样报 "type vector does not exist"。
CREATE EXTENSION IF NOT EXISTS vector;

-- pg_trgm: 三元组(trigram)模糊匹配扩展。
-- 用途: 混合检索的"关键词通道"——向量检索管语义，trigram 管
-- 精确字段子串(如"逾期文件第3列"这类必须命中原词的查询)。
-- 选型说明: 中文分词扩展 zhparser 需要自行编译安装，阶段 3 评估后
-- 若 trigram 效果不够再升级，先用最轻的方案。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ========== 阶段 1：文档入库链路 ==========

-- 文档切块表: RAG 的核心存储，一份解析后的文档按切分策略存成多行
CREATE TABLE IF NOT EXISTS doc_chunk (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_id        TEXT         NOT NULL, -- 文档唯一标识，如 huaxin_corp_api_v1（虚构资方）
    doc_title     TEXT         NOT NULL, -- 文档标题，回答溯源时展示
    source_path   TEXT         NOT NULL, -- 源文件相对路径，引用溯源的定位依据
    section_path  TEXT,                  -- 章节路径，如 '3 日末文件 > 3.2 逾期文件格式'
    chunk_index   INT          NOT NULL, -- 文档内块序号，从 0 开始
    content       TEXT         NOT NULL, -- 切分后的正文
    char_count    INT          NOT NULL, -- 字符数，阶段 3 分析"切分粒度 vs 召回率"用
    embedding     VECTOR(1024) NOT NULL, -- 向量，维度见下方说明
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (doc_id, chunk_index)         -- 幂等保障: 同一文档重复入库前先按 doc_id 删旧
);

-- 向量索引: HNSW（分层可导航小世界图）
-- 为什么不用 IVFFlat: IVFFlat 建索引要求表里已有数据做聚类中心，
-- 且数据分布变化后需要重建；HNSW 随时可建、查询性能稳定，
-- 个人项目数据量（几千 chunk）下内存开销完全可接受。
-- vector_cosine_ops: 用余弦距离，文本向量检索的常规选择。
CREATE INDEX IF NOT EXISTS idx_doc_chunk_hnsw
    ON doc_chunk USING hnsw (embedding vector_cosine_ops);

-- 关键词索引: trigram 倒排，让 content 的 LIKE '%xx%' 和相似度搜索走索引
CREATE INDEX IF NOT EXISTS idx_doc_chunk_content_trgm
    ON doc_chunk USING gin (content gin_trgm_ops);

-- 设计决策: embedding 为什么是 1024 维？
-- 智谱 embedding-3 原生输出 2048 维，但 API 支持 dimensions 参数
-- 降维。选 1024: 存储减半、HNSW 检索更快，语义损失对召回率的
-- 影响在阶段 3 的 20 条评估集上实测——这个数字就是简历素材。

-- ========== 阶段 4：字段映射 + 模型调用留痕 ==========

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
