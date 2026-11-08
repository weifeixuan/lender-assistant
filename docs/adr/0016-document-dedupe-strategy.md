# ADR-0016：文档判重 = SQL 形状预过滤 + 内容 SHA-256 + 进程内条带锁

- 状态：已采纳（2026-11-08）
- 相关：`rag/service/impl/DocumentServiceImpl`、`rag/mapper/DocChunkMapper`、`resources/mapper/DocChunkMapper.xml`、`docs/schema.sql`、ADR-0004、ADR-0006

## 背景

上传接口允许重复提交同一份文档，并发上传（双击、客户端重试）也可能同时到达。旧判重实现有两个问题：

1. **成本随库增长**：把全库文档的 `doc_chunk` 正文读进内存后逐条比较，数据和内存占用都不受控；
2. **判重与写入之间没有互斥**：两个并发上传会同时判定「不重复」，然后各写一份，库内出现重复文档。

约束：本阶段不想改表结构（不新增 `content_hash` 列、不加唯一索引），只改应用层判重。

## 决策

采用三段式判重，在「切分之后、向量化之前」执行：

1. **SQL 形状预过滤**：`DocChunkMapper.listDocumentsByShape(chunkCount, charCountTotal)` 用
   `GROUP BY doc_id HAVING COUNT(*) = 切片数 AND SUM(char_count) = 总字符数`
   只返回形状一致的候选文档，全库正文不再进内存。
2. **内容 SHA-256**：对候选逐个读取 chunk 行，按「章节路径 + 正文」的有序序列拼成规范文本后做 SHA-256；切片之间用 `\u0000` 分隔，避免拼接歧义。哈希不含 `docId` / 文件名 / `doc_title`，因此同一文件重复上传、内容相同但文件名不同都能判中。
3. **进程内条带锁**：`ReentrantLock[64]`，按内容哈希的 `hashCode` 取模选锁；锁内完成「判重 → 向量化 → 写库」。命中重复则直接复用既有文档的统计值，向量化与写库耗时记 0。

**明确不使用数据库锁**。理由：

- 现有 schema 没有 `content_hash` 列，加唯一索引需要改表、回填存量并处理并发 upsert 冲突；
- `SELECT ... FOR UPDATE` / advisory lock 会长期占用连接，并引入锁等待与死锁面；
- 当前部署是单实例，进程内锁已经覆盖「并发重复上传」的窗口；把数据库锁的成本提前引入没有收益。

## 被否决的替代方案

- **全库正文读内存比对**：实现最简单，但内存与 IO 随库增长线性膨胀，是本次要修的问题本身。
- **只按形状判重**：切片数 + 总字符数相同不代表内容相同，误判会让不同文档被当成重复，不可接受。
- **新增 `content_hash` 列 + 唯一索引**：最稳妥，也是多实例的最终方案；但需要改 schema、回填存量、处理冲突，单实例阶段不值得。
- **数据库 advisory lock / `SELECT FOR UPDATE`**：能跨实例，但占用连接、增加死锁排查成本，与当前单实例部署不匹配。
- **完全不判重**：重复上传白跑向量化，库内出现重复文档，来源面板重复展示。

## 单实例前提与多实例扩展

条带锁只在单个 JVM 内生效，前提是**当前单实例部署**（compose 一份 app）。扩展到多实例时按优先级：

1. `doc_chunk`（或独立 `document_digest` 表）增加 `content_hash` + 唯一索引，让数据库兜底；写入冲突按幂等返回既有文档；
2. 若还要避免重复向量化成本，用 Postgres advisory lock（如 `pg_advisory_xact_lock(hashtext(hash))`）把「判重 + 写入」串行化；
3. 或用 Redis 分布式锁（租约 + 续期），但会引入额外组件，非必要不选。

## 后果

- 64 条带意味着哈希取模到同一段的**不同内容**会短暂互相等待（最坏约 1/64 串行化），可接受。
- 形状必须完全一致才进入哈希比较：同一内容被不同切分策略切出不同块数时判不中，会重新入库；切分策略变更后属预期行为。
- 候选仍需逐文档读取 chunk 行，成本 = 形状一致候选数 × 块数；形状条件已把候选压到极少数。
- 空文档没有判重对象，直接走 `replaceDocument` 删旧。
- 判重逻辑与 schema 解耦；若后续加 `content_hash` 列，迁移与回填须按 ADR-0006 同步 `docs/schema.sql`。

## 影响

- `DocChunkMapper` 新增 `listDocumentsByShape`、`listChunkLines`；`DocumentServiceImpl` 新增条带锁、`contentHash` / `linesHash` / `canonicalLine`。
- 测试覆盖：并发同内容只入库一次、SQL 形状过滤（不调用全量列表）、Mapper XML 契约。
