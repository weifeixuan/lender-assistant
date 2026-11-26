# GLOSSARY · 融资智能工作台

本项目的领域词汇表。写 issue / 测试名 / 设计说明时，**统一使用本表定义的词**，不要另造同义词。
表内只收录已经成型的概念；尚未讨论清楚的词等 `/domain-modeling` 厘清后再补。

## 业务领域词

| 词 | 含义 | 代码锚点 |
|---|---|---|
| 资方（lender） | 提供资金的机构（银行 / 租赁公司）。本项目所有示例数据均为全脱敏虚构值 | `docs/gen_sample_docs.py` 生成的示例文档 |
| docId | 一篇已入库文档的稳定标识（如 `huaxin_corp_api_v1`）；重复上传同 docId 即覆盖重建 | `DocumentAdminController`、`DocChunkMapper` |
| chunk（分块） | 文档经解析、按章节切分后的最小检索单元，携带 sectionPath / 字符数 / 向量 | `rag/model/dto/ChunkDTO`、`rag/model/entity/ChunkEntity` |
| 召回（recall / hit） | 检索命中的 chunk。字段含 vectorDistance（向量距离）、keywordHits（关键词命中数）、bothChannels（双通道命中标记） | `SearchService`、`VectorHitDTO` / `KeywordHitDTO` |
| 混合检索（hybrid search） | 向量召回（pgvector HNSW）与关键词召回（pg_trgm trigram）合并排序；模式取值 `vector` / `keyword` / `hybrid` | `SearchService`、`EvalController`（mode 参数） |
| 映射（mapping）/ 字段映射 | 资方接口文档字段 → 内部标准字段的对应关系。含 lenderFieldName / lenderValue / sourceQuote / confidence | `extract/model/entity/FieldMappingEntity`、`ExtractController` |
| 内部标准字段（internal field） | 本项目约定的内部字段目录，映射表的目标端 | `extract/service/InternalFieldCatalog` |
| 映射状态 | `PENDING`（待确认）/ `CONFIRMED`（已确认）/ `CORRECTED`（已修正，人工改过值，留痕） | `ExtractController.confirm` |
| 熔断（circuit breaker） | 模型连续失败后快速失败、降级为可读提示，不崩溃 | `resilience/ResilientLlmExecutor`、`QaServiceImpl` |
| 留痕（llm call log） | 每次模型调用写一行（场景 / 供应商 / 耗时 / token / OK·FAIL），旁路降级：写库失败不挡主链路 | `resilience/LlmCallLogger`、`llm_call_log` 表 |

## 多轮对话词

| 词 | 含义 | 代码锚点 |
|---|---|---|
| 完整轮（complete round） | 同一 roundId 下 USER 与 ASSISTANT 消息均为 DONE 且非固定提示（rule）回复的一轮；不完整的轮次不参与模型上下文 | `ConversationHistoryAssembler`、ADR-0008 |
| 回放窗口（replay window） | 单次追问回放给模型的最近完整轮序列；实际范围由轮数上限与字符预算共同决定，以更紧者为准 | `rag.qa.history-max-rounds` / `rag.qa.history-max-chars`、`ConversationHistoryAssembler` |
| 滚动摘要（rolling summary） | 会话级背景梗概：回放窗口外更早轮次与已有摘要的合并产物；仅作背景补充，不参与检索 query 改写与路由判定 | `conversation.summary` 列、`docs/prd/conversation-rolling-summary.md` |
| 摘要覆盖边界（summary boundary） | 被滚动摘要覆盖到的最后一个完整轮的 roundId；摘要是否注入以「边界轮早于窗口最旧轮」为准 | `conversation.summary_boundary_round_id` 列、ADR-0020 |

## 账户与权限词

| 词 | 含义 | 代码锚点 |
|---|---|---|
| 账户（account） | 可登录的操作主体；用户名唯一且创建后不可改，口令只存 BCrypt 摘要，禁用后全部会话立即失效 | `account/model/entity/AppUserEntity`、`AccountController` |
| 角色（role） | 权限点的集合；账户可挂多个角色，权限取并集。自定义角色被账户引用时不能删除 | `account/model/entity/AppRoleEntity`、`RoleController` |
| 权限点（permission code） | 代码中固定定义的原子能力标识（如 `settings.accounts.manage`）；角色引用它，前后端都按它判断，不按角色名硬编码 | `common/constant/PermissionCode`、`role_permission` 表 |
| 登录态（session） | 一次登录产生的服务端会话：库中只存令牌 SHA-256 摘要与过期时间，登出 / 改密 / 禁用即失效 | `AuthService`、`AuthInterceptor`、`user_session` 表 |
| 内置角色（builtin role） | `app_role.builtin=true` 的系统角色；当前只有「系统管理员」，始终拥有全部权限，禁止改删 | `RoleServiceImpl`、`docs/schema.sql` |
| 初始管理员（bootstrap admin） | 启动自举创建的管理员账户；口令只从环境变量读取，缺省时跳过建号并告警，不阻断启动 | `AuthBootstrapServiceImpl`、`AuthBootstrapRunner` |

## 横切（Web 层）词

| 词 | 含义 | 代码锚点 |
|---|---|---|
| 统一响应体（ApiResponse） | 所有普通接口的对外响应形状：`{code, msg, data}`。成功 `code=200`、`msg="ok"`；失败 `data` 为 null | `common/web/ApiResponse` |
| 响应包装（ApiResponseBodyAdvice） | 在消息转换器出站前把普通返回值包成统一响应体的 `ResponseBodyAdvice` | `common/web/ApiResponseBodyAdvice` |
| 原始响应（@RawResponse） | 显式声明**跳过**统一包装的接口（SSE、`/health` 探针）。可标方法或类 | `common/annotation/RawResponse` |
| 全局异常处理器（GlobalExceptionHandler） | 把异常统一转成 `ApiResponse` 的 `@RestControllerAdvice`；对外不暴露堆栈 / 内部类名 / SQL | `common/web/GlobalExceptionHandler` |
| 业务异常（BizException） | 业务失败的统一异常类型，携带 ErrorCode；禁止再用裸 RuntimeException 做流程控制 | `common/exception/BizException` |
| 错误码（ErrorCode） | 失败的语义枚举：`SUCCESS(200)` / `PARAM_INVALID(400)` / `PAYLOAD_TOO_LARGE(413)` / `NOT_FOUND(404)` / `DOC_NOT_FOUND(404)` / `CONVERSATION_NOT_FOUND(404)` / `CONVERSATION_BUSY(409)` / `EVAL_BUSY(409)` / `UNAUTHORIZED(401)` / `FORBIDDEN(403)` / `USERNAME_EXISTS(409)` / `ROLE_NAME_EXISTS(409)` / `ROLE_IN_USE(409)` / `ROLE_PROTECTED(400)` / `LLM_FAILED(1001)` / `INTERNAL_ERROR(500)` | `common/exception/ErrorCode` |
| 控制台切面（ControllerLogAspect） | 打印 controller 进入 / 返回 / 耗时日志的 AOP 切面，只切 HTTP 边界 | `common/web/ControllerLogAspect` |
