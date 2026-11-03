# 08: 历史装配与生成策略签名

**What to build:** 追问时模型能真正"记得"之前的对话：后端按 `roundId` 取出最近若干个完整且都成功的轮次，整轮裁剪到字符上限，按时间正序传给模型；失败或停止的轮次只展示不回放。检索 query 也会带上最近一条历史用户提问，让"那二套呢"这类指代能检索到正确资料。

**涉及层次:** message 的历史查询、历史装配服务、`AnswerGeneratorStrategy` 接口与两个实现、检索 query 构造、配置项、单测。

**Blocked by:** 03, 07

**Status:** ready-for-agent

**Estimate:** 2 小时

- [ ] `AnswerGeneratorStrategy` 新签名接收有序历史（`role` + `content`）、当前问题与召回结果，两个实现同步适配
- [ ] OpenAI 兼容实现按顺序把历史映射为对话消息，最后追加当前用户消息；Mock 实现签名一致并忽略历史
- [ ] 历史按 `roundId` 分组，每组必须同时有一条 USER 和一条 ASSISTANT 且状态均为 `DONE`；缺任一条整轮丢弃
- [ ] 最多回放最近 6 轮；总字符超 2000 时从最旧一轮开始整轮丢弃
- [ ] `FAILED` / `STOPPED` 轮次不进入上下文
- [ ] 新建会话历史为空，首问行为不变
- [x] 检索时把「当前问题」与「最近一条历史 USER 正文」**分段**传给 `SearchService`；无可用历史时上一轮为 `null`
- [x] `rag.qa.history-max-rounds` 与 `rag.qa.history-max-chars` 可配置并生效
- [x] 单测覆盖 6 轮上限、2000 字符整轮裁剪、非 `DONE` 剔除、缺一条剔除、检索分段传参
- [x] 追问关键词 token 按段分配配额：当前问题保底一半，余额让给另一段（见下方修复记录）

## Comments

### 2026-11-03 追问检索分段传参 + 关键词 token 配额

**问题现场**：会话 `da14dd96-…` 第一轮问「单笔代收字段请求接口有哪些」，第二轮只问「响应字段呢？」
（`sources` 与第一轮相同：4 条全是单笔代付 + 交易查询，无任何代收切片）。根因是检索把两轮问题用 `\n`
拼成整串后，关键词通道按顺序取前 8 个 token：上一轮长问题独占了 8 个配额，`响应 / 应字 / 段呢` 全被截掉，
两轮的关键词通道输入完全相同。

**改法（不动 HTTP 契约，只改内部签名）**：

- `SearchService.hybridSearch(String query, int topK)` → `hybridSearch(String question, String previousQuestion, int topK)`。
  选「分段传参」而不是在 `SearchServiceImpl` 里按 `\n` 切分：按换行切分只是一个隐式约定，
  一旦历史文本自身含换行就会悄悄切错；显式两段参数由调用方保证来源，语义不会漂移。
  同步改调用方 `QaServiceImpl`、`DocumentAdminController`（管理台检索传 `null`）与 `QaServiceTest`。
- 向量通道行为不变：仍用「上一轮 + 换行 + 当前问题」的整体文本做 embedding，避免改动已锁定的召回基线。
- 关键词通道：`keywordTokens(question, previousQuestion)` 按段分 8 个配额——当前问题保底 4，其余给上一轮；
  一段不足时余额让给另一段；两段都溢出时当前问题优先；跨段去重时当前问题的词优先保留。
- RED：先加 3 条测试（`keywordQuotaReservesCurrentQuestionTokens`、
  `keywordQuotaPrioritizesCurrentWhenBothOverflow`、`keywordQuotaGivesSpareToPreviousWhenCurrentIsShort`），
  在旧实现下实际拿到 `[单笔, 笔代, 代收, 收字, 字段, 段请, 请求, 求接]`（当前问题 token 全部丢失），实现后转绿。
- 本例修复后 token：`[响应, 应字, 字段, 段呢, 单笔, 笔代, 代收, 收字]`；`响应` 进入关键词通道，
  代收切片（chunk 4）的 `hits` 由 3 升到 4，应从关键词第 3 名升到第 1 名，RRF 由 `0.01587`（被 topK=4 截掉）
  升到 `0.01639`（进入前 2）。真实 `/api/qa` 复测结果见票 10 收尾记录。
