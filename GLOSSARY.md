# GLOSSARY · 资方接入智能助手

本项目的领域词汇表。写 issue / 测试名 / 设计说明时，**统一使用本表定义的词**，不要另造同义词。
表内只收录已经成型的概念；尚未讨论清楚的词等 `/domain-modeling` 厘清后再补。

## 业务领域词

| 词 | 含义 | 代码锚点 |
|---|---|---|
| 资方（lender） | 提供资金的机构（银行 / 租赁公司）。本项目所有示例数据均为全脱敏虚构值 | `docs/gen_sample_docs.py` 生成的示例文档 |
| docId | 一篇已入库文档的稳定标识（如 `huaxin_corp_api_v1`）；重复上传同 docId 即覆盖重建 | `DocumentAdminController`、`DocChunkMapper` |
| chunk（分块） | 文档经解析、按章节切分后的最小检索单元，携带 sectionPath / 字符数 / 向量 | `rag/model/Chunk`、`ChunkRow` |
| 召回（recall / hit） | 检索命中的 chunk。字段含 vectorDistance（向量距离）、keywordHits（关键词命中数）、bothChannels（双通道命中标记） | `SearchService`、`VectorHit` / `KeywordHit` |
| 混合检索（hybrid search） | 向量召回（pgvector HNSW）与关键词召回（pg_trgm trigram）合并排序；模式取值 `vector` / `keyword` / `hybrid` | `SearchService`、`EvalController`（mode 参数） |
| 映射（mapping）/ 字段映射 | 资方接口文档字段 → 内部标准字段的对应关系。含 lenderFieldName / lenderValue / sourceQuote / confidence | `extract/model/FieldMapping`、`ExtractController` |
| 内部标准字段（internal field） | 本项目约定的内部字段目录，映射表的目标端 | `extract/service/InternalFieldCatalog` |
| 映射状态 | `PENDING`（待确认）/ `CONFIRMED`（已确认）/ `CORRECTED`（已修正，人工改过值，留痕） | `ExtractController.confirm` |
| 熔断（circuit breaker） | 模型连续失败后快速失败、降级为可读提示，不崩溃 | `resilience/ResilientLlmExecutor`、`QaServiceImpl` |
| 留痕（llm call log） | 每次模型调用写一行（场景 / 供应商 / 耗时 / token / OK·FAIL），旁路降级：写库失败不挡主链路 | `resilience/LlmCallLogger`、`llm_call_log` 表 |

## 横切（Web 层）词

| 词 | 含义 | 代码锚点 |
|---|---|---|
| 统一响应体（ApiResponse） | 所有普通接口的对外响应形状：`{code, msg, data}`。成功 `code=200`、`msg="ok"`；失败 `data` 为 null | `common/dto/ApiResponse` |
| 响应包装（ApiResponseBodyAdvice） | 在响应体出站时把 controller 普通返回值包成统一响应体的机制 | `common/web/ApiResponseBodyAdvice` |
| 原始响应（@RawResponse） | 显式声明**跳过**统一包装的接口（SSE、`/health` 探针）。可标方法或类 | `common/annotation/RawResponse` |
| 全局异常处理器（GlobalExceptionHandler） | 把异常统一转成 `ApiResponse` 的 `@RestControllerAdvice`；对外不暴露堆栈 / 内部类名 / SQL | `common/web/GlobalExceptionHandler` |
| 业务异常（BizException） | 业务失败的统一异常类型，携带 ErrorCode；禁止再用裸 RuntimeException 做流程控制 | `common/exception/BizException` |
| 错误码（ErrorCode） | 失败的语义枚举：`SUCCESS(200)` / `PARAM_INVALID(400)` / `NOT_FOUND(404)` / `LLM_FAILED(1001)` / `INTERNAL_ERROR(500)` | `common/exception/ErrorCode` |
| 控制台切面（ControllerLogAspect） | 打印 controller 进入 / 返回 / 耗时日志的 AOP 切面，只切 HTTP 边界 | `common/web/ControllerLogAspect` |
