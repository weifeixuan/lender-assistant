# 03: 摘要模型客户端

**What to build:** 摘要能力可以调模型拿到正文：新增 `LlmScene.SUMMARY` 场景与 conversation 领域 `client/` 非流式客户端，走 `ResilientLlmExecutor`（留痕 scene=summary、熔断、重试），超时与路由/计划同构复用 `NON_STREAM_TIMEOUT`（10s）；摘要生成 prompt 落 `QaPrompts`（铁律：只依据输入轮次原文与已有摘要，不编造）。本票不接触发与写回；硬截断由合并服务（票 04）负责，client 返回完整正文。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] `LlmScene` 加 `SUMMARY`；`docs/schema.sql` 中 `llm_call_log.scene` 行内注释与 `COMMENT ON COLUMN` 取值清单同步 `summary`
- [x] `SchemaContractTest` 的 scene 断言清单同步（先 RED 后 GREEN）
- [x] client 接口 + impl 与路由客户端同构；`NON_STREAM_TIMEOUT` 出现第三处，按 Rule of Three 抽取为共享常量，三处引用
- [x] `QaPrompts` 新增摘要生成指令；`QaPromptsContractTest` 逐字断言同步
- [x] client 单测（桩模型响应）；调用失败上抛，静默降级语义归调用方
- [x] `mvn test` 全绿
