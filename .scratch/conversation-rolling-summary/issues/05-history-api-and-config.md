# 05: 历史接口出参与配置

**What to build:** 前端能拿到摘要元信息：历史消息接口响应新增 `summary` / `summaryBoundaryRoundId` 两字段（未生成过摘要的会话为 null 的三态）；`rag.qa.summary.max-chars` 配置落 `application.yml`（环境变量占位 + 默认 500）。前端以 `summary` 非空作为提示条显示条件。

**Blocked by:** 01, 04

**Status:** resolved

- [x] 历史接口响应加两字段，控制器测试覆盖：无摘要 null / 有摘要非空 / 边界 id 非空
- [x] `application.yml` 加 `rag.qa.summary.max-chars: ${RAG_QA_SUMMARY_MAX_CHARS:500}`
- [x] `mvn test` 全绿
