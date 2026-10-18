# 领域分包重构 + 数据访问 MyBatis 化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `AGENTS.md` §0.2/§0.3/§11 把 `src/main/java` 从「顶层技术包」重构为「按领域分包 + 领域内分层」，并把三处 JdbcTemplate 直写数据访问（`DocumentStore` / `FieldMappingStore` / `LlmCallLogger` 的写库部分）统一迁移为 MyBatis Mapper + XML。

**Architecture:** 两个阶段串行推进。阶段 A 只搬包与抽 Service 接口（不碰 SQL，每步都能编译 + 全量测试绿）；阶段 B 逐块引入 MyBatis（先加依赖与 Mapper 基建，再切换调用方、最后删旧类）。数据访问的方向固定为 `controller → service → mapper`，`@Transactional` 全部从 Store 迁到 Service，pgvector 专属语法只出现在 Mapper XML。

**Tech Stack:** JDK 21 · Spring Boot 3.5.16 · MyBatis（`mybatis-spring-boot-starter:3.0.5`）· PostgreSQL 16 + pgvector · JUnit 5 + Mockito。

**已定决策（本次重构的前提，执行时不要改）：**
1. `controller/ChatController` + `service/ChatService` **删除**（阶段0 连通件，已被 `/api/qa` 覆盖；`index.html` 未调用 `/api/chat`）。
2. `HealthController` 归入新建的 **`ops` 运维领域**（`ops/controller/HealthController`），`/health` 端点保留（`docs/plan_phase5.md` 的 compose 验收依赖它）。
3. 编排型 Service **一律抽接口 + `service/impl/XxxServiceImpl`**：`QaService` / `EvalService` / `SearchService` / `DocumentService` / `ExtractService` / `DraftService`。
4. **不做** git worktree，直接在 `main` 工作区按 task 逐个 commit。
5. 本计划**不含** §11「代码治理」条目（魔法值归集、controller Map→record、`Executors`→`ThreadPoolExecutor`）：它们属于独立主题，见文末「明确排除」。

**本计划的书写约定（重要）：**
- **新建文件**给出完整代码，可直接粘贴。
- **纯搬包的文件**（类体不变）以「精确改写清单」形式给出（改哪一行、改成什么），不重复贴全文——类体逐字保留。
- 涉及 `record` 与 `Map` 的 JSON 键名，已逐一核对：`FieldMappingStore.listMappings` 旧返回的键名与新的 `FieldMappingRow` record 组件名逐字相同，**前端 `index.html` 无需改动**。

**每步的验证命令统一为（PowerShell 5，路径已用正斜杠）：**

```powershell
mvn -q test-compile     # 编译 main + test，能扫出全部调用点（只 compile 会漏 src/test）
mvn -q test             # 全量测试（改过签名后先删 target/test-classes，见下）
Remove-Item -Recurse -Force target/test-classes   # 仅在全量 mvn test 之前执行，防陈旧 .class 假绿
```

---

## 阶段 A：包结构与 Service 接口化（不碰 SQL）

### Task A1: `SearchService` 归位 `rag/service` 并抽接口

**Files:**
- Create: `src/main/java/com/lender/assistant/rag/service/SearchService.java`
- Create: `src/main/java/com/lender/assistant/rag/service/impl/SearchServiceImpl.java`
- Delete: `src/main/java/com/lender/assistant/rag/SearchService.java`
- Move: `src/test/java/com/lender/assistant/rag/SearchServiceTest.java` → `src/test/java/com/lender/assistant/rag/service/SearchServiceTest.java`
- Modify: `src/main/java/com/lender/assistant/controller/DocumentAdminController.java:3`
- Modify: `src/main/java/com/lender/assistant/rag/eval/EvalService.java:5`
- Modify: `src/main/java/com/lender/assistant/rag/qa/QaService.java:3`

- [ ] **Step 1: 新建接口 `rag/service/SearchService.java`**

```java
package com.lender.assistant.rag.service;

import java.util.List;
import java.util.Map;

/**
 * 混合检索（向量通道 + 关键词通道）。抽接口的理由：这是 rag 领域对外唯一的检索契约，
 * 后续（阈值调优、重排、缓存）只替换实现，不动调用方。
 */
public interface SearchService {

    List<Map<String, Object>> hybridSearch(String query, int topK);

    /** mode 取值 vector / keyword / hybrid，阶段 3 评估的实验开关 */
    List<Map<String, Object>> search(String query, int topK, String mode);
}
```

- [ ] **Step 2: 新建实现 `rag/service/impl/SearchServiceImpl.java`**

内容 = 现 `rag/SearchService.java` 全文，仅做以下改写：

```text
第 1 行：package com.lender.assistant.rag;      →  package com.lender.assistant.rag.service.impl;
第 5 行：import com.lender.assistant.rag.store.DocumentStore;   （保持不变，Phase B 才换成 Mapper）
新增行：import com.lender.assistant.rag.service.SearchService;
类声明：public class SearchService {            →  public class SearchServiceImpl implements SearchService {
hybridSearch 与 search 两个 @Override 方法前各加一行 @Override（共 2 处）
类 Javadoc 保留原文
```

`SearchServiceImpl` 的构造器、`maxDistance` 字段、`chunkId`/`base` 私有方法一律逐字保留。

- [ ] **Step 3: 删除旧文件并改写 3 个调用方的 import**

```text
删除：src/main/java/com/lender/assistant/rag/SearchService.java

controller/DocumentAdminController.java:3
  - import com.lender.assistant.rag.SearchService;
  + import com.lender.assistant.rag.service.SearchService;

rag/eval/EvalService.java:5
  - import com.lender.assistant.rag.SearchService;
  + import com.lender.assistant.rag.service.SearchService;

rag/qa/QaService.java:3
  - import com.lender.assistant.rag.SearchService;
  + import com.lender.assistant.rag.service.SearchService;
```

- [ ] **Step 4: 移动测试类并改构造**

```text
移动：src/test/java/com/lender/assistant/rag/SearchServiceTest.java
   →  src/test/java/com/lender/assistant/rag/service/SearchServiceTest.java
第 1 行：package com.lender.assistant.rag;  →  package com.lender.assistant.rag.service;
新增 import：import com.lender.assistant.rag.service.impl.SearchServiceImpl;
第 33 行字段：private SearchService service;  （保持接口类型，无需改）
第 39 行：service = new SearchService(embeddingClient, store, -1);   →  service = new SearchServiceImpl(embeddingClient, store, -1);
```

其余断言（RRF 分数 `0.03252`/`0.01639`/`0.01613`、token 过滤 `[逾期, 期第]`）逐字保留——这是锁住 RRF 行为的特征测试，不许改动期望值。

- [ ] **Step 5: 编译并跑测试**

```powershell
mvn -q test-compile
mvn -q test
```
Expected: `BUILD SUCCESS`，测试数 56 例全绿（本 task 零行为变更）。

- [ ] **Step 6: 提交**

```powershell
git add src/main/java/com/lender/assistant/rag/service src/main/java/com/lender/assistant/rag/SearchService.java src/test/java/com/lender/assistant/rag/service src/test/java/com/lender/assistant/rag/SearchServiceTest.java src/main/java/com/lender/assistant/controller/DocumentAdminController.java src/main/java/com/lender/assistant/rag/eval/EvalService.java src/main/java/com/lender/assistant/rag/qa/QaService.java
git commit -m "重构(包结构): SearchService 归位 rag/service 并抽接口 + Impl"
```

---

### Task A2: `rag/qa/*` 归位 `rag/service`（QaService 抽接口）

**Files:**
- Create: `src/main/java/com/lender/assistant/rag/service/QaService.java`
- Create: `src/main/java/com/lender/assistant/rag/service/impl/QaServiceImpl.java`
- Move: `src/main/java/com/lender/assistant/rag/qa/AnswerGenerator.java` → `src/main/java/com/lender/assistant/rag/service/AnswerGenerator.java`
- Move: `src/main/java/com/lender/assistant/rag/qa/MockAnswerGenerator.java` → `src/main/java/com/lender/assistant/rag/service/MockAnswerGenerator.java`
- Move: `src/main/java/com/lender/assistant/rag/qa/DeepSeekAnswerGenerator.java` → `src/main/java/com/lender/assistant/rag/service/DeepSeekAnswerGenerator.java`
- Delete: `src/main/java/com/lender/assistant/rag/qa/`（整包，搬迁后为空）
- Move: `src/test/java/com/lender/assistant/rag/qa/QaServiceTest.java` → `src/test/java/com/lender/assistant/rag/service/QaServiceTest.java`
- Move: `src/test/java/com/lender/assistant/rag/qa/MockToolAnswerTest.java` → `src/test/java/com/lender/assistant/rag/service/MockToolAnswerTest.java`
- Modify: `src/main/java/com/lender/assistant/controller/QaController.java:3`

- [ ] **Step 1: 新建接口 `rag/service/QaService.java`**

```java
package com.lender.assistant.rag.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答编排（检索 → 拼 Prompt → 模型生成 → SSE 流式）。
 * 抽接口的理由：SSE 契约（事件名 sources/delta/done/error）是对外协议，
 * 前端按它分发；实现细节（线程池、Prompt 文案）不该被调用方感知。
 */
public interface QaService {

    /** 立即返回 emitter，检索/生成/send 在独立工作线程执行（真流式的必要条件） */
    SseEmitter answer(String question, int topK);
}
```

- [ ] **Step 2: 新建实现 `rag/service/impl/QaServiceImpl.java`**

内容 = 现 `rag/qa/QaService.java` 全文，仅做以下改写：

```text
第 1 行：package com.lender.assistant.rag.qa;   →  package com.lender.assistant.rag.service.impl;
第 3 行：import com.lender.assistant.rag.SearchService;   （A1 已改成 com.lender.assistant.rag.service.SearchService，保持）
新增行：import com.lender.assistant.rag.service.QaService;
第 29-30 行注解与声明：
  @Service
  public class QaService {                      →  public class QaServiceImpl implements QaService {
第 54 行 public SseEmitter answer(String question, int topK) {  →  上一行加 @Override，签名不变
```

`streamExecutor`（`Executors.newFixedThreadPool(8, ...)`）、`streamAnswer`、`systemPrompt`、`userPrompt`、`send`、`shutdownExecutor` 逐字保留——`Executors` 改造属 §11 代码治理，本计划明确排除（见文末）。

- [ ] **Step 3: 搬迁 3 个策略类 + 删除旧包**

```text
rag/qa/AnswerGenerator.java          →  rag/service/AnswerGenerator.java
  第 1 行：package com.lender.assistant.rag.qa;  →  package com.lender.assistant.rag.service;   （其余逐字不变）

rag/qa/MockAnswerGenerator.java      →  rag/service/MockAnswerGenerator.java
  第 1 行：package com.lender.assistant.rag.qa;  →  package com.lender.assistant.rag.service;   （其余逐字不变）

rag/qa/DeepSeekAnswerGenerator.java  →  rag/service/DeepSeekAnswerGenerator.java
  第 1 行：package com.lender.assistant.rag.qa;  →  package com.lender.assistant.rag.service;   （其余逐字不变）
```

这两个实现类内部对 `com.lender.assistant.tools.*`、`com.lender.assistant.resilience.*` 用的是全限定名（未 import），搬迁后仍然有效，不需改。

- [ ] **Step 4: 改写调用方与测试**

```text
controller/QaController.java:3
  - import com.lender.assistant.rag.qa.QaService;
  + import com.lender.assistant.rag.service.QaService;

移动：src/test/java/com/lender/assistant/rag/qa/QaServiceTest.java
   →  src/test/java/com/lender/assistant/rag/service/QaServiceTest.java
第 1 行：package com.lender.assistant.rag.qa;  →  package com.lender.assistant.rag.service;
第 3 行：import com.lender.assistant.rag.SearchService;  →  import com.lender.assistant.rag.service.SearchService;
新增 import：import com.lender.assistant.rag.service.impl.QaServiceImpl;
第 37 行：private QaService qaService;  （保持接口类型）
第 68 行：qaService = new QaService(searchService, generator, executor);  →  qaService = new QaServiceImpl(searchService, generator, executor);
第 163 行：QaService svc = new QaService(searchService, broken, ...)  →  QaService svc = new QaServiceImpl(searchService, broken, ...)
（内部类 RecordingGenerator implements AnswerGenerator、awaitAsyncDone 全部逐字保留）

移动：src/test/java/com/lender/assistant/rag/qa/MockToolAnswerTest.java
   →  src/test/java/com/lender/assistant/rag/service/MockToolAnswerTest.java
第 1 行：package com.lender.assistant.rag.qa;  →  package com.lender.assistant.rag.service;   （其余逐字不变）
```

- [ ] **Step 5: 删除旧包**

```text
删除目录：src/main/java/com/lender/assistant/rag/qa/
删除目录：src/test/java/com/lender/assistant/rag/qa/
```

- [ ] **Step 6: 编译并跑测试**

```powershell
mvn -q test-compile
mvn -q test
```
Expected: `BUILD SUCCESS`，56 例全绿。特别注意 `QaServiceTest` 的 4 个异步断言（`awaitAsyncDone`）必须仍通过——这是 2026-10-17 修过的坑。

- [ ] **Step 7: 提交**

```powershell
git add src/main/java/com/lender/assistant/rag/service src/main/java/com/lender/assistant/rag/qa src/test/java/com/lender/assistant/rag/service src/test/java/com/lender/assistant/rag/qa src/main/java/com/lender/assistant/controller/QaController.java
git commit -m "重构(包结构): rag/qa 归位 rag/service，QaService 抽接口 + Impl"
```

---

### Task A3: `rag/eval/EvalService` 归位 `rag/service` 并抽接口

**Files:**
- Create: `src/main/java/com/lender/assistant/rag/service/EvalService.java`
- Create: `src/main/java/com/lender/assistant/rag/service/impl/EvalServiceImpl.java`
- Delete: `src/main/java/com/lender/assistant/rag/eval/`（整包）
- Modify: `src/main/java/com/lender/assistant/controller/EvalController.java:3`

- [ ] **Step 1: 新建接口 `rag/service/EvalService.java`**

```java
package com.lender.assistant.rag.service;

import java.util.Map;

/**
 * 检索质量评估（跑评估集 → 算 recall@K / MRR → 出 markdown 报告）。
 * 评估集资源固定在 resources/eval/evalset.json，不随本次迁移移动。
 */
public interface EvalService {

    Map<String, Object> run(int topK, String mode) throws Exception;
}
```

- [ ] **Step 2: 新建实现 `rag/service/impl/EvalServiceImpl.java`**

内容 = 现 `rag/eval/EvalService.java` 全文，仅做以下改写：

```text
第 1 行：package com.lender.assistant.rag.eval;  →  package com.lender.assistant.rag.service.impl;
第 5 行：import com.lender.assistant.rag.SearchService;   （A1 已改成 com.lender.assistant.rag.service.SearchService，保持）
新增行：import com.lender.assistant.rag.service.EvalService;
第 31-32 行：@Service / public class EvalService {  →  public class EvalServiceImpl implements EvalService {
第 48 行 public Map<String, Object> run(int topK, String mode) throws Exception {  →  上一行加 @Override
```

两个嵌套 record（`EvalItem`、`ItemResult`）留在 `EvalServiceImpl` 内部，作为实现私有契约；`loadEvalSet`、`buildMarkdown` 与 `new ClassPathResource("eval/evalset.json")` 逐字保留。

- [ ] **Step 3: 改写调用方并删除旧包**

```text
controller/EvalController.java:3
  - import com.lender.assistant.rag.eval.EvalService;
  + import com.lender.assistant.rag.service.EvalService;

删除目录：src/main/java/com/lender/assistant/rag/eval/
```

- [ ] **Step 4: 编译并跑测试**

```powershell
mvn -q test-compile
mvn -q test
```
Expected: `BUILD SUCCESS`，56 例全绿（EvalService 无单测，仅需编译通过）。

- [ ] **Step 5: 提交**

```powershell
git add src/main/java/com/lender/assistant/rag/service src/main/java/com/lender/assistant/rag/eval src/main/java/com/lender/assistant/controller/EvalController.java
git commit -m "重构(包结构): EvalService 归位 rag/service 并抽接口，删除 rag/eval 空包"
```

---

### Task A4: `extract` 与 `draft` 两领域归位（含 `model` 层与接口化）

> 两个领域必须同一个 task 内完成：`draft` 的 5 个类都 import `extract` 的 `FieldMapping`/`InternalFieldCatalog`，分两次改会重复改同一批 draft 文件。

**Files:**
- Create: `src/main/java/com/lender/assistant/extract/model/FieldMapping.java`
- Create: `src/main/java/com/lender/assistant/extract/model/InternalField.java`
- Create: `src/main/java/com/lender/assistant/extract/service/ExtractService.java`
- Create: `src/main/java/com/lender/assistant/extract/service/impl/ExtractServiceImpl.java`
- Move→`extract/service/`: `ExtractGenerator.java`、`MockExtractGenerator.java`、`DeepSeekExtractGenerator.java`、`InternalFieldCatalog.java`
- Create: `src/main/java/com/lender/assistant/draft/service/DraftService.java`
- Create: `src/main/java/com/lender/assistant/draft/service/impl/DraftServiceImpl.java`
- Move→`draft/service/`: `DraftGenerator.java`、`MockDraftGenerator.java`、`DeepSeekDraftGenerator.java`
- Modify: `src/main/java/com/lender/assistant/controller/ExtractController.java:3-5`
- Modify: `src/main/java/com/lender/assistant/controller/DraftController.java:3`
- Modify tests: `ExtractServiceTest`、`MockExtractGeneratorTest`、`InternalFieldCatalogTest`、`FieldMappingStoreTest`、`DraftServiceTest`、`MockDraftGeneratorTest`

- [ ] **Step 1: 新建 `extract/model` 两个 record（`FieldMapping` 内容逐字照搬，只改包名）**

`src/main/java/com/lender/assistant/extract/model/FieldMapping.java`：

```java
package com.lender.assistant.extract.model;

/**
 * 一条字段映射：内部标准字段 ↔ 资方侧的对应信息。
 *
 * lenderFieldName：资方文档里这个信息的"字段名/标签"（如 merNo、网关地址）；
 * lenderValue：资方侧的具体取值（如 https://fake-xxx.example.com/gateway）；
 * sourceQuote：该值在文档中的原文出处行——引用溯源要求，抽取必须可回查；
 * confidence：置信度 (0,1]，规则精确命中 0.9 / 语义定位 0.7，前端按它排序提示人工复核。
 */
public record FieldMapping(
        String internalFieldId,
        String internalFieldName,
        String lenderFieldName,
        String lenderValue,
        String sourceQuote,
        double confidence) {
}
```

`src/main/java/com/lender/assistant/extract/model/InternalField.java`：

```java
package com.lender.assistant.extract.model;

/**
 * 内部标准字段：映射表"我方一侧"的字段定义。
 *
 * 为什么用 record 而不是枚举：字段集要出现在 JSON Schema 文本、
 * 前端下拉、数据库字段对照里，record + 静态目录比 enum 更容易序列化展示。
 */
public record InternalField(String id, String name, String description) {
}
```

- [ ] **Step 2: 新建 `extract/service/ExtractService.java` 接口**

```java
package com.lender.assistant.extract.service;

import com.lender.assistant.extract.model.FieldMapping;

import java.util.List;

/**
 * 结构化抽取编排：加载已入库文档全文 → 经韧性执行器调抽取器。
 * 抽接口的理由：抽取是"非流式一锤子调用 + 允许重试"的固定契约，
 * 上层（Controller）只关心出参，不关心走的是 Mock 还是 DeepSeek。
 */
public interface ExtractService {

    /** @throws IllegalArgumentException 文档不存在或未入库 */
    List<FieldMapping> extractFromDoc(String docId);
}
```

- [ ] **Step 3: 新建 `extract/service/impl/ExtractServiceImpl.java`**

内容 = 现 `extract/ExtractService.java` 全文，仅做以下改写：

```text
第 1 行：package com.lender.assistant.extract;  →  package com.lender.assistant.extract.service.impl;
第 3 行：import com.lender.assistant.resilience.ResilientLlmExecutor;   （保持不变）
第 4 行：import com.lender.assistant.rag.store.DocumentStore;           （保持不变，Phase B 才换成 DocumentService）
新增行：import com.lender.assistant.extract.model.FieldMapping;
新增行：import com.lender.assistant.extract.service.ExtractService;
类声明：public class ExtractService {  →  public class ExtractServiceImpl implements ExtractService {
extractFromDoc(...) 方法前加 @Override
```

- [ ] **Step 4: 搬迁 `extract` 的 4 个策略/常量类**

```text
extract/ExtractGenerator.java         →  extract/service/ExtractGenerator.java
  第 1 行 package 改 extract.service；新增 import com.lender.assistant.extract.model.FieldMapping;（原为同包，现跨包）

extract/MockExtractGenerator.java     →  extract/service/MockExtractGenerator.java
  第 1 行 package 改 extract.service；新增 import com.lender.assistant.extract.model.FieldMapping;
  （InternalFieldCatalog 与搬迁后同包，无需 import）

extract/DeepSeekExtractGenerator.java →  extract/service/DeepSeekExtractGenerator.java
  第 1 行 package 改 extract.service；
  新增 import com.lender.assistant.extract.model.FieldMapping;
  新增 import com.lender.assistant.extract.model.InternalField;

extract/InternalFieldCatalog.java     →  extract/service/InternalFieldCatalog.java
  第 1 行 package 改 extract.service；新增 import com.lender.assistant.extract.model.InternalField;
  （按 §11 迁移表原文归位 service 层，非 model——不要自行改判）
```

- [ ] **Step 5: 新建 `draft/service/DraftService.java` 接口**

```java
package com.lender.assistant.draft.service;

/**
 * 代码草稿生成编排：加载映射表 → 经韧性执行器调生成器 → 附人工复核声明。
 * 抽接口的理由：DraftResult 是 API 契约（前端按字段名渲染），
 * 复核声明永不省略，这两点由接口固定下来。
 */
public interface DraftService {

    /** 生成结果；reviewNotice 恒非空——"AI 代码必须人工复核"红线在契约层固化 */
    record DraftResult(String docId, String provider, String reviewNotice,
                       int mappingsUsed, String draft) {}

    DraftResult generate(String docId);
}
```

- [ ] **Step 6: 新建 `draft/service/impl/DraftServiceImpl.java`**

内容 = 现 `draft/DraftService.java` 全文，仅做以下改写：

```text
第 1 行：package com.lender.assistant.draft;  →  package com.lender.assistant.draft.service.impl;
第 3 行：import com.lender.assistant.extract.FieldMapping;        →  import com.lender.assistant.extract.model.FieldMapping;
第 4 行：import com.lender.assistant.extract.FieldMappingStore;   （保持不变，Phase B 才换）
第 5 行：import com.lender.assistant.resilience.ResilientLlmExecutor;   （保持不变）
新增行：import com.lender.assistant.draft.service.DraftService;
删除：原类的嵌套 record DraftResult（已上移到接口）
类声明：public class DraftService {  →  public class DraftServiceImpl implements DraftService {
类内所有 DraftResult 引用改为 DraftService.DraftResult（构造处 1 处、返回类型 1 处）
generate(...) 方法前加 @Override
REVIEW_NOTICE 常量保留在实现类（static final，非 private——测试与实现同包时可用）
```

- [ ] **Step 7: 搬迁 `draft` 的 3 个策略类**

```text
draft/DraftGenerator.java         →  draft/service/DraftGenerator.java
  第 1 行 package 改 draft.service；import com.lender.assistant.extract.FieldMapping → extract.model.FieldMapping

draft/MockDraftGenerator.java     →  draft/service/MockDraftGenerator.java
  第 1 行 package 改 draft.service；
  import ...extract.FieldMapping → extract.model.FieldMapping；import ...extract.InternalFieldCatalog → extract.service.InternalFieldCatalog

draft/DeepSeekDraftGenerator.java →  draft/service/DeepSeekDraftGenerator.java
  第 1 行 package 改 draft.service；import ...extract.FieldMapping → extract.model.FieldMapping
```

- [ ] **Step 8: 删除旧文件与空包**

```text
删除：src/main/java/com/lender/assistant/extract/ 下的 ExtractService.java、ExtractGenerator.java、
      MockExtractGenerator.java、DeepSeekExtractGenerator.java、InternalFieldCatalog.java、
      FieldMapping.java、InternalField.java
删除：src/main/java/com/lender/assistant/draft/ 下的 DraftService.java、DraftGenerator.java、
      MockDraftGenerator.java、DeepSeekDraftGenerator.java
（删除后 extract/ 与 draft/ 只剩 controller、service、impl、model、mapper 子包，根目录无游离类）
```

- [ ] **Step 9: 改写两个 Controller 的 import**

```text
controller/ExtractController.java:3
  - import com.lender.assistant.extract.ExtractService;
  + import com.lender.assistant.extract.service.ExtractService;
controller/ExtractController.java:5
  - import com.lender.assistant.extract.InternalFieldCatalog;
  + import com.lender.assistant.extract.service.InternalFieldCatalog;
（第 4 行 FieldMappingStore 保持不动，Phase B 才换）

controller/DraftController.java:3
  - import com.lender.assistant.draft.DraftService;
  + import com.lender.assistant.draft.service.DraftService;
（DraftController 内 DraftService.DraftResult 的写法保持有效）
```

- [ ] **Step 10: 改写 6 个测试类**

```text
extract/ExtractServiceTest.java  →  extract/service/ExtractServiceTest.java
  第 1 行：package com.lender.assistant.extract;  →  package com.lender.assistant.extract.service;
  新增 import：import com.lender.assistant.extract.model.FieldMapping;
  新增 import：import com.lender.assistant.extract.service.impl.ExtractServiceImpl;
  第 30 行：private ExtractService service;  保持接口类型
  第 40 行：service = new ExtractService(generator, store, executor);  →  new ExtractServiceImpl(generator, store, executor);
  （其余断言与 stub 逐字保留）

extract/MockExtractGeneratorTest.java  →  extract/service/MockExtractGeneratorTest.java
  第 1 行：package 改 extract.service；新增 import com.lender.assistant.extract.model.FieldMapping;
  新增 import com.lender.assistant.extract.model.InternalField;
  （RUIJIN_TEXT fixture 与全部断言逐字保留）

extract/InternalFieldCatalogTest.java  →  extract/service/InternalFieldCatalogTest.java
  第 1 行：package com.lender.assistant.extract;  →  package com.lender.assistant.extract.service;
  新增 import com.lender.assistant.extract.service.InternalFieldCatalog;（或同包不写，二选一：保留同包引用即可，不写 import）
  新增 import com.lender.assistant.extract.model.InternalField;

extract/FieldMappingStoreTest.java
  仅改 import：import com.lender.assistant.extract.FieldMapping;  →  import com.lender.assistant.extract.model.FieldMapping;
  （本 Phase 仍测 FieldMappingStore，Phase B 再改为测 ExtractServiceImpl 的映射读写）

draft/DraftServiceTest.java  →  draft/service/DraftServiceTest.java
  第 1 行：package com.lender.assistant.draft;  →  package com.lender.assistant.draft.service;
  第 3 行：import com.lender.assistant.extract.FieldMapping;  →  import com.lender.assistant.extract.model.FieldMapping;
  新增 import：import com.lender.assistant.draft.service.impl.DraftServiceImpl;
  第 28 行：private DraftService service;  保持接口类型
  第 38 行：service = new DraftService(generator, store, executor);  →  new DraftServiceImpl(generator, store, executor);
  第 71 行：DraftService.DraftResult result  （写法保持有效）

draft/MockDraftGeneratorTest.java  →  draft/service/MockDraftGeneratorTest.java
  第 1 行：package com.lender.assistant.draft;  →  package com.lender.assistant.draft.service;
  第 3 行：import com.lender.assistant.extract.FieldMapping;  →  import com.lender.assistant.extract.model.FieldMapping;
```

- [ ] **Step 11: 编译并跑测试**

```powershell
Remove-Item -Recurse -Force target/test-classes
mvn -q test
```
Expected: `BUILD SUCCESS`，56 例全绿。这一步改动的类最多，务必全量跑而不是 `-DskipTests compile`。

- [ ] **Step 12: 提交**

```powershell
git add src/main/java/com/lender/assistant/extract src/main/java/com/lender/assistant/draft src/test/java/com/lender/assistant/extract src/test/java/com/lender/assistant/draft src/main/java/com/lender/assistant/controller/ExtractController.java src/main/java/com/lender/assistant/controller/DraftController.java
git commit -m "重构(包结构): extract/draft 按领域归位（model/service/impl），Service 抽接口 + Impl"
```

---

### Task A5: 删除 Chat 连通件与顶层 `service/` 包

**Files:**
- Delete: `src/main/java/com/lender/assistant/controller/ChatController.java`
- Delete: `src/main/java/com/lender/assistant/service/ChatService.java`
- Delete: `src/main/java/com/lender/assistant/service/`（整包）

- [ ] **Step 1: 确认无残留引用**

```powershell
```
用 Grep 在 `src/` 下搜 `ChatService|ChatController|/api/chat`，期望零命中（`index.html` 已在前置勘察中确认不调用该端点）。

- [ ] **Step 2: 删除文件与包**

```text
删除：src/main/java/com/lender/assistant/controller/ChatController.java
删除：src/main/java/com/lender/assistant/service/ChatService.java
删除目录：src/main/java/com/lender/assistant/service/
```

- [ ] **Step 3: 编译并跑测试**

```powershell
mvn -q test-compile
mvn -q test
```
Expected: `BUILD SUCCESS`，56 例全绿（无测试引用这两个类）。

- [ ] **Step 4: 提交**

```powershell
git add -u src/main/java/com/lender/assistant/controller/ChatController.java src/main/java/com/lender/assistant/service
git commit -m "重构(包结构): 删除阶段0 Chat 连通件（已被 /api/qa 覆盖）与顶层 service 包"
```

---

### Task A6: 新建 `ops` 运维领域，收尾删除顶层 `controller/` 包

**Files:**
- Create: `src/main/java/com/lender/assistant/ops/controller/HealthController.java`
- Move: `src/main/java/com/lender/assistant/controller/DocumentAdminController.java` → `src/main/java/com/lender/assistant/rag/controller/DocumentAdminController.java`
- Move: `src/main/java/com/lender/assistant/controller/QaController.java` → `src/main/java/com/lender/assistant/rag/controller/QaController.java`
- Move: `src/main/java/com/lender/assistant/controller/EvalController.java` → `src/main/java/com/lender/assistant/rag/controller/EvalController.java`
- Move: `src/main/java/com/lender/assistant/controller/ExtractController.java` → `src/main/java/com/lender/assistant/extract/controller/ExtractController.java`
- Move: `src/main/java/com/lender/assistant/controller/DraftController.java` → `src/main/java/com/lender/assistant/draft/controller/DraftController.java`
- Delete: `src/main/java/com/lender/assistant/controller/HealthController.java`
- Delete: `src/main/java/com/lender/assistant/controller/`（整包）

> **计划补漏（2026-10-18 执行时发现）**：本节原 Files 清单只列了 HealthController 的搬迁，但 Step 4 的终态结构要求 `rag/controller` / `extract/controller` / `draft/controller`，且 `AGENTS.md` §11 迁移表早已把 5 个业务 Controller 的目标位置定为领域 `controller/`。原清单遗漏这 5 个文件的搬迁，导致「删除顶层 `controller/`」实际无法完成。此处补上。

- [ ] **Step 1: 新建 `ops/controller/HealthController.java`**

```java
package com.lender.assistant.ops.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 运维领域：进程存活探针。GET /health 返回 {status, app, time}。
 *
 * 为什么单列 ops 领域而不是塞进 rag/extract：健康检查与业务语义无关，
 * 未来会加入 PG 连通性、熔断器状态等运维视图（ResilientLlmExecutor.circuitState()），
 * 放业务包会被迫反向依赖。compose 验收脚本依赖本端点，不可删除。
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "app", "lender-assistant",
                "time", LocalDateTime.now().toString()
        );
    }
}
```

- [ ] **Step 2: 搬迁 5 个业务 Controller 到领域 `controller/` 包**

**只改每个文件的 `package` 行**（类体逐字保留；全仓 Grep 已确认没有任何其它文件以 FQN 引用这些 Controller，故无调用方需改）：

```text
controller/DocumentAdminController.java  →  rag/controller/DocumentAdminController.java
  package com.lender.assistant.controller;  →  package com.lender.assistant.rag.controller;

controller/QaController.java             →  rag/controller/QaController.java
  package com.lender.assistant.controller;  →  package com.lender.assistant.rag.controller;

controller/EvalController.java           →  rag/controller/EvalController.java
  package com.lender.assistant.controller;  →  package com.lender.assistant.rag.controller;

controller/ExtractController.java        →  extract/controller/ExtractController.java
  package com.lender.assistant.controller;  →  package com.lender.assistant.extract.controller;

controller/DraftController.java          →  draft/controller/DraftController.java
  package com.lender.assistant.controller;  →  package com.lender.assistant.draft.controller;
```

（这 5 个 Controller 各自 import 的服务类已用全限定名，搬迁后无需改 import。）

- [ ] **Step 3: 删除旧文件与顶层包**

```text
删除：src/main/java/com/lender/assistant/controller/HealthController.java
删除目录：src/main/java/com/lender/assistant/controller/   （5 个业务 Controller 已搬迁、Health 已重建到 ops，本目录应已无 .java）
```

- [ ] **Step 4: 校验包结构终态**

用 Glob `src/main/java/**/*.java` 逐项核对，终态应为：

```text
com.lender.assistant
├── LenderAssistantApplication.java
├── rag/{controller,service,service/impl,mapper,model,parser,splitter,embedder}/
├── extract/{controller,service,service/impl,mapper,model}/
├── draft/{controller,service,service/impl}/
├── tools/{ToolIntentRouter,LenderQueryTools}
├── resilience/{LlmCallLogger,ResilientLlmExecutor,mapper,model}
└── ops/controller/HealthController
```
（`mapper` / `model` 下多项在阶段 B 落地；本步只需确认**不存在**顶层 `controller/`、`service/`、`rag/store/`、`rag/qa/`、`rag/eval/`。）

- [ ] **Step 5: 编译并跑测试**

```powershell
mvn -q test-compile
mvn -q test
```
Expected: `BUILD SUCCESS`，56 例全绿。

- [ ] **Step 6: 提交**

```powershell
git add src/main/java/com/lender/assistant/ops src/main/java/com/lender/assistant/controller src/main/java/com/lender/assistant/rag/controller src/main/java/com/lender/assistant/extract/controller src/main/java/com/lender/assistant/draft/controller
git commit -m "阶段6(part6): 新建 ops 运维领域承载 /health，业务 Controller 归位领域包，删除顶层 controller 包"
```

---

### Task A7: 同步更新 `AGENTS.md` 的结构规范

> 依据 §0.3「新建包前必须能在 §0.2 结构图里指认它的位置」——新建了 `ops`、`rag/model`、`resilience/model`，规范文档必须同步，否则后续 Agent 会照着旧图又建错。

**Files:**
- Modify: `AGENTS.md`（§0.2 结构图、§11 两张迁移表）

- [ ] **Step 1: 改写 §0.2 结构图**

把 §0.2 的 ```text 代码块整体替换为：

```text
com.lender.assistant
├── rag/                       # RAG 领域
│   ├── controller/            #   QaController EvalController DocumentAdminController
│   ├── service/               #   QaService(+impl/) EvalService(+impl/) SearchService(+impl/) DocumentService(+impl/)
│   │                          #   ；策略组件平铺：AnswerGenerator / Mock·DeepSeekAnswerGenerator
│   ├── mapper/                #   DocChunkMapper（+ resources/mapper/DocChunkMapper.xml）
│   ├── model/                 #   跨层 record：ChunkRow / DocChunkLine / DocContent / DocSummary / VectorHit / KeywordHit
│   ├── parser/ splitter/ embedder/   # 领域内组件，保留自有子包（不硬塞进三层）
│   └── （评估逻辑在 service/；评估集资源固定在 resources/eval/evalset.json）
├── extract/                   # 结构化抽取领域
│   ├── controller/ service/ service/impl/ mapper/ model/
├── draft/                     # 代码草稿生成领域
│   ├── controller/ service/ service/impl/
├── tools/                     # Function Calling 工具（LenderQueryTools 等）——无 HTTP 入口，不建 controller/
├── resilience/                # 韧性（ResilientLlmExecutor / LlmCallLogger）——无 HTTP 入口
│   ├── mapper/                #   LlmCallLogMapper（+ resources/mapper/LlmCallLogMapper.xml）
│   └── model/                 #   LlmCallLogRecord
├── ops/                       # 运维领域（无业务语义）
│   └── controller/            #   HealthController（GET /health）
├── common/                    # 跨领域内容（领域包装不下时才进这里）
│   ├── constant/              #   *Constants（禁实例化）
│   ├── util/                  #   *Utils（静态方法 + private 构造器，如 PgVectorUtils）
│   └── dto/                   #   跨模块 DTO
├── config/                    #   @Configuration / @Bean 装配，集中于此
├── exception/                 #   全局异常处理 + 业务异常
└── LenderAssistantApplication.java
```

- [ ] **Step 2: 改写 §11 两张迁移表**

「数据访问迁移」三条 checkbox 的可执行部分改为（保留原文案，补上真实路径）：

```text
- [ ] `rag/store/DocumentStore`（JdbcTemplate）→ `rag/mapper/DocChunkMapper`（+ `resources/mapper/DocChunkMapper.xml`）
- [ ] `extract/FieldMappingStore`（实际在 extract 根，不在 extract/store）→ `extract/mapper/FieldMappingMapper`（+ XML）
- [ ] `resilience/LlmCallLogger`（JdbcTemplate 留痕）→ 写库部分落到 `resilience/mapper/LlmCallLogMapper`（+ XML），LlmCallLogger 保留为调用侧装饰器
```

「包结构迁移」表的两行「待定」改为已定：

```text
| `controller/ChatController` + `service/ChatService` | 删除（阶段0 连通件，已被 /api/qa 覆盖） |
| `controller/HealthController` | `ops/controller`（新建 ops 运维领域） |
```

并在表尾删除：「顶层 `controller/`、`service/` 空包迁移后删除」一行改为已完成口径。

- [ ] **Step 3: 提交**

```powershell
git add AGENTS.md
git commit -m "docs(规范): AGENTS.md §0.2/§11 同步 ops 领域、model 层与已定的空壳件处置"
```

---

## 阶段 B：数据访问 MyBatis 化

### Task B1: 引入 MyBatis 依赖与配置

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: `pom.xml` 加依赖**

在 `spring-boot-starter-jdbc` 依赖块之后插入：

```xml
        <!-- MyBatis：统一数据访问口径（AGENTS.md §6）。
             不引 MyBatis-Plus（谓词构造器会诱导把业务判断写进数据层）；
             版本 3.0.5 对应 MyBatis 3.5.x，兼容 Spring Boot 3.5 -->
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
            <version>3.0.5</version>
        </dependency>
```

- [ ] **Step 2: `application.yml` 加 MyBatis 配置**

在 `spring:` 块之后、`rag:` 块之前插入：

```yaml
mybatis:
  # Mapper XML 统一放 resources/mapper（§6.2），与接口不在同一 classpath 位置，必须显式指定
  mapper-locations: classpath:mapper/*.xml
  configuration:
    # 下划线列名 → 驼峰属性（resultMap 的 <arg column> 仍显式写出，不依赖隐式映射）
    map-underscore-to-camel-case: true
```

- [ ] **Step 3: 编译并跑测试**

```powershell
mvn -q test-compile
mvn -q test
```
Expected: `BUILD SUCCESS`，56 例全绿（尚无代码使用 MyBatis；此步只验证依赖可解析、不影响既有链路）。

- [ ] **Step 4: 提交**

```powershell
git add pom.xml src/main/resources/application.yml
git commit -m "阶段6(part1): 引入 mybatis-spring-boot-starter 与 mapper-locations 配置"
```

---

### Task B2: `resilience` 留痕迁移 —— `LlmCallLogMapper` 基建

**Files:**
- Create: `src/main/java/com/lender/assistant/resilience/model/LlmCallLogRecord.java`
- Create: `src/main/java/com/lender/assistant/resilience/mapper/LlmCallLogMapper.java`
- Create: `src/main/resources/mapper/LlmCallLogMapper.xml`

- [ ] **Step 1: 新建 `resilience/model/LlmCallLogRecord.java`**

```java
package com.lender.assistant.resilience.model;

/**
 * 一次模型调用的留痕行（llm_call_log）。
 *
 * 为什么用 record 而不是 7 个方法参数：§2.8 规定方法参数 ≤ 4 个；
 * 留痕字段天然是一组整体，收敛为 record 同时让 Mapper 传参稳定。
 * token 可为 null——Mock 调用没有真实计量，诚实留空比编数字好。
 */
public record LlmCallLogRecord(
        String scene,
        String provider,
        Integer durationMs,
        Integer promptTokens,
        Integer completionTokens,
        String status,
        String errorMsg) {
}
```

- [ ] **Step 2: 新建 `resilience/mapper/LlmCallLogMapper.java`**

```java
package com.lender.assistant.resilience.mapper;

import com.lender.assistant.resilience.model.LlmCallLogRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * llm_call_log 的唯一 INSERT 落点（§6.1：SQL 只存在于 mapper 层）。
 * 不含 @Transactional、不含 try/catch——旁路降级（写库失败不反噬业务）留给调用侧 LlmCallLogger。
 */
@Mapper
public interface LlmCallLogMapper {

    int insert(@Param("log") LlmCallLogRecord log);
}
```

- [ ] **Step 3: 新建 `src/main/resources/mapper/LlmCallLogMapper.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<!-- llm_call_log 留痕写入。cost_cny 有 DEFAULT 0，不在 INSERT 列里（与旧 SQL 一致） -->
<mapper namespace="com.lender.assistant.resilience.mapper.LlmCallLogMapper">

    <insert id="insert">
        INSERT INTO llm_call_log
            (scene, provider, duration_ms, prompt_tokens, completion_tokens, status, error_msg)
        VALUES
            (#{log.scene}, #{log.provider}, #{log.durationMs}, #{log.promptTokens},
             #{log.completionTokens}, #{log.status}, #{log.errorMsg})
    </insert>

</mapper>
```

- [ ] **Step 4: 编译（此时新类无人调用，不删旧路径）**

```powershell
mvn -q test-compile
```
Expected: `BUILD SUCCESS`。此步只落地基建，`LlmCallLogger` 仍走 JdbcTemplate，测试应仍全绿。

- [ ] **Step 5: 提交**

```powershell
git add src/main/java/com/lender/assistant/resilience/mapper src/main/java/com/lender/assistant/resilience/model src/main/resources/mapper/LlmCallLogMapper.xml
git commit -m "阶段6(part2): LlmCallLogMapper + XML + LlmCallLogRecord 基建"
```

---

### Task B3: `LlmCallLogger` 切到 Mapper（含 5 个测试类同步）

**Files:**
- Modify: `src/main/java/com/lender/assistant/resilience/LlmCallLogger.java`
- Modify: `src/test/java/com/lender/assistant/resilience/LlmCallLoggerTest.java`
- Modify: `src/test/java/com/lender/assistant/resilience/ResilientLlmExecutorTest.java`
- Modify: `src/test/java/com/lender/assistant/extract/service/ExtractServiceTest.java`
- Modify: `src/test/java/com/lender/assistant/draft/service/DraftServiceTest.java`
- Modify: `src/test/java/com/lender/assistant/rag/service/QaServiceTest.java`

- [ ] **Step 1: 改写 `LlmCallLogger.java`**

整类替换为：

```java
package com.lender.assistant.resilience;

import com.lender.assistant.resilience.mapper.LlmCallLogMapper;
import com.lender.assistant.resilience.model.LlmCallLogRecord;
import org.springframework.stereotype.Repository;

import java.util.function.Supplier;

/**
 * 模型调用留痕器（每次模型调用留痕——耗时/token/成本）。
 *
 * 设计：装饰器/旁路模式——业务代码只写 logger.record(scene, provider, () -> ...)，
 * 留痕对主链路零侵入；且留痕写库失败绝不反噬业务（旁路降级）。
 *
 * 数据访问已迁到 LlmCallLogMapper（§11）：本类只保留"包住一次调用 + 旁路降级"的编排职责，
 * 不再持有 JdbcTemplate。旁路降级（catch 后不上抛）是刻意的——见 write()。
 */
@Repository
public class LlmCallLogger {

    private final LlmCallLogMapper mapper;

    public LlmCallLogger(LlmCallLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 包住一次模型调用：成功记 OK，失败记 FAIL 并原样上抛异常（不吞不换壳） */
    public <T> T record(String scene, String provider, Supplier<T> call) {
        long t0 = System.currentTimeMillis();
        try {
            T result = call.get();
            write(scene, provider, (int) (System.currentTimeMillis() - t0), null, null, "OK", null);
            return result;
        } catch (RuntimeException e) {
            write(scene, provider, (int) (System.currentTimeMillis() - t0), null, null, "FAIL", e.getMessage());
            throw e;
        }
    }

    /**
     * 手动打点：真实模型实现自己拿到 token 计量时调用
     * （Spring AI 的 Usage 在响应里，流式场景由实现方汇总后打点）。
     */
    public void recordUsage(String scene, String provider, Integer promptTokens,
                            Integer completionTokens, int durationMs, String status, String errorMsg) {
        write(scene, provider, durationMs, promptTokens, completionTokens, status, errorMsg);
    }

    private void write(String scene, String provider, int durationMs, Integer promptTokens,
                       Integer completionTokens, String status, String errorMsg) {
        try {
            mapper.insert(new LlmCallLogRecord(scene, provider, durationMs,
                    promptTokens, completionTokens, status, errorMsg));
        } catch (RuntimeException ignored) {
            // 留痕是旁路：log 表写不进去（没跑 docs/schema.sql 等）不允许拖垮问答/抽取主链路
        }
    }
}
```

- [ ] **Step 2: 改 `LlmCallLoggerTest.java`**

```text
第 1 行 package 不变（com.lender.assistant.resilience）
- import org.springframework.jdbc.core.JdbcTemplate;
- import org.springframework.jdbc.core.ArgumentCaptor（保留 ArgumentCaptor）
+ import com.lender.assistant.resilience.mapper.LlmCallLogMapper;
+ import com.lender.assistant.resilience.model.LlmCallLogRecord;

第 26 行：private JdbcTemplate jdbc;      →  private LlmCallLogMapper mapper;
第 32 行：jdbc = mock(JdbcTemplate.class);  →  mapper = mock(LlmCallLogMapper.class);
第 32 行：logger = new LlmCallLogger(jdbc); →  logger = new LlmCallLogger(mapper);

recordsSuccess：
  ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
  verify(jdbc).update(contains("llm_call_log"), args.capture());
  Object[] row = args.getValue();
  →  ArgumentCaptor<LlmCallLogRecord> args = ArgumentCaptor.forClass(LlmCallLogRecord.class);
     verify(mapper).insert(args.capture());
     LlmCallLogRecord row = args.getValue();
  其余断言改字段访问：row[0]→row.scene(), row[1]→row.provider(), row[5]→row.status(),
  row[2] 的耗时断言 → assertEquals("OK", row.status()) 之后加：
      assertNotNull(row.durationMs()); assertTrue(row.durationMs() >= 0, "耗时毫秒必须非负");

recordsFailureAndRethrows：
  verify(mapper).insert(args.capture());
  row.scene()=="qa"、row.status()=="FAIL"、row.errorMsg().contains("502")
  （assertSame(boom, thrown) 逐字保留）

loggingFailureDoesNotBreakBusiness：
  doThrow(new RuntimeException("log 表写不进去")).when(mapper).insert(any(LlmCallLogRecord.class));

manualRecordWithTokens：
  verify(mapper).insert(any(LlmCallLogRecord.class));
```

- [ ] **Step 3: 改 `ResilientLlmExecutorTest.java`**

```text
- import org.springframework.jdbc.core.JdbcTemplate;
+ import com.lender.assistant.resilience.mapper.LlmCallLogMapper;
第 27 行：logger = new LlmCallLogger(mock(JdbcTemplate.class));  →  logger = new LlmCallLogger(mock(LlmCallLogMapper.class));
（熔断/重试/chaos 的 5 个断言逐字保留——这是状态机特征测试）
```

- [ ] **Step 4: 改 3 个构造 `LlmCallLogger` 的编排测试**

```text
extract/service/ExtractServiceTest.java
  - import org.springframework.jdbc.core.JdbcTemplate;
  + import com.lender.assistant.resilience.mapper.LlmCallLogMapper;
  第 38-39 行：executor = spy(new ResilientLlmExecutor(
                  new LlmCallLogger(mock(JdbcTemplate.class)), 3, 10, 2, false));
            →  executor = spy(new ResilientLlmExecutor(
                  new LlmCallLogger(mock(LlmCallLogMapper.class)), 3, 10, 2, false));

draft/service/DraftServiceTest.java
  - import org.springframework.jdbc.core.JdbcTemplate;
  + import com.lender.assistant.resilience.mapper.LlmCallLogMapper;
  第 36 行：logger = spy(new LlmCallLogger(mock(JdbcTemplate.class)));
         →  logger = spy(new LlmCallLogger(mock(LlmCallLogMapper.class)));

rag/service/QaServiceTest.java
  第 65-66 行：new com.lender.assistant.resilience.LlmCallLogger(
                    mock(org.springframework.jdbc.core.JdbcTemplate.class))
               →  new com.lender.assistant.resilience.LlmCallLogger(
                    mock(com.lender.assistant.resilience.mapper.LlmCallLogMapper.class))
  第 165-166 行：同样的替换（第二处构造）
```

- [ ] **Step 5: 编译并跑测试**

```powershell
Remove-Item -Recurse -Force target/test-classes
mvn -q test
```
Expected: `BUILD SUCCESS`，56 例全绿。`LlmCallLoggerTest` 断言全绿是关键信号——它证明"留痕契约（场景/状态/错误信息落库）+ 旁路降级不反噬"在换掉数据访问实现后仍然成立。

- [ ] **Step 6: 提交**

```powershell
git add src/main/java/com/lender/assistant/resilience/LlmCallLogger.java src/test/java/com/lender/assistant/resilience src/test/java/com/lender/assistant/extract/service/ExtractServiceTest.java src/test/java/com/lender/assistant/draft/service/DraftServiceTest.java src/test/java/com/lender/assistant/rag/service/QaServiceTest.java
git commit -m "阶段6(part3): LlmCallLogger 留痕切到 LlmCallLogMapper（去 JdbcTemplate）"
```

---

### Task B4: `rag` 数据访问迁移 —— `DocChunkMapper` + `DocumentService` 基建

**Files:**
- Create: `src/main/java/com/lender/assistant/common/util/PgVectorUtils.java`
- Create: `src/main/java/com/lender/assistant/rag/model/ChunkRow.java`
- Create: `src/main/java/com/lender/assistant/rag/model/DocChunkLine.java`
- Create: `src/main/java/com/lender/assistant/rag/model/DocContent.java`
- Create: `src/main/java/com/lender/assistant/rag/model/DocSummary.java`
- Create: `src/main/java/com/lender/assistant/rag/model/VectorHit.java`
- Create: `src/main/java/com/lender/assistant/rag/model/KeywordHit.java`
- Create: `src/main/java/com/lender/assistant/rag/mapper/DocChunkMapper.java`
- Create: `src/main/resources/mapper/DocChunkMapper.xml`
- Create: `src/main/java/com/lender/assistant/rag/service/DocumentService.java`
- Create: `src/main/java/com/lender/assistant/rag/service/impl/DocumentServiceImpl.java`

- [ ] **Step 1: 新建 `common/util/PgVectorUtils.java`**

```java
package com.lender.assistant.common.util;

/**
 * pgvector 文本字面量工具。
 *
 * 为什么放 common/util 而不是 rag 领域：float[] → "[0.1,...]" 是**数据库表示层**的关切
 * （MyBatis XML 无法调 Java，向量必须以字符串参数传入，见 §6.7），不是 RAG 语义关切；
 * rag 领域没有 util 子包，故按 §0.2 归 common/util。
 */
public final class PgVectorUtils {

    private PgVectorUtils() {
    }

    /** float[] → pgvector 文本字面量 "[0.1,0.2,...]" */
    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
```

- [ ] **Step 2: 新建 `rag/model` 的 6 个 record**

`ChunkRow.java`：

```java
package com.lender.assistant.rag.model;

/**
 * doc_chunk 的待插入行。
 * embedding 传的是 pgvector 文本字面量（"[0.1,...]"）而非 float[]——见 §6.7 与 PgVectorUtils：
 * MyBatis 只能拿到字符串参数，由调用方（DocumentAdminController）统一转换。
 */
public record ChunkRow(String sectionPath, int index, String content, int charCount, String embedding) {
}
```

`DocChunkLine.java`：

```java
package com.lender.assistant.rag.model;

/** 单条 chunk 行（不含 embedding）：loadDocContent 按 chunk_index 取全文用 */
public record DocChunkLine(String docTitle, String sectionPath, String content) {
}
```

`DocContent.java`：

```java
package com.lender.assistant.rag.model;

/** 文档全文（按 chunk 顺序拼接）+ 标题，结构化抽取的输入 */
public record DocContent(String docTitle, String text) {
}
```

`DocSummary.java`：

```java
package com.lender.assistant.rag.model;

/** 已入库文档概览（doc_chunk 按 doc_id 聚合） */
public record DocSummary(String docId, String docTitle, int chunkCount) {
}
```

`VectorHit.java`：

```java
package com.lender.assistant.rag.model;

/** 向量召回命中（distance = 余弦距离，越小越相似） */
public record VectorHit(long id, String docId, String docTitle, String sectionPath,
                        String content, double distance) {
}
```

`KeywordHit.java`：

```java
package com.lender.assistant.rag.model;

/** 关键词召回命中（hits = 命中 token 数，越大越相关） */
public record KeywordHit(long id, String docId, String docTitle, String sectionPath,
                         String content, int hits) {
}
```

> **关于 record 与 MyBatis 传参**：MyBatis 的 `Reflector` 会把 record 的私有 final 字段登记为可读属性，故 `#{r.sectionPath}` 这类 OGNL 取值可用；结果映射则一律走显式 `<constructor>` 位置参数，**不依赖** `argNameBasedConstructorAutoMapping` 与编译期 `-parameters`。

- [ ] **Step 3: 新建 `rag/mapper/DocChunkMapper.java`**

```java
package com.lender.assistant.rag.mapper;

import com.lender.assistant.rag.model.ChunkRow;
import com.lender.assistant.rag.model.DocChunkLine;
import com.lender.assistant.rag.model.DocSummary;
import com.lender.assistant.rag.model.KeywordHit;
import com.lender.assistant.rag.model.VectorHit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * doc_chunk 表的唯一读写落点（§6.1：SQL 只存在于 mapper 层）。
 *
 * 约定：
 * - pgvector 专属语法（CAST(... AS vector)、<=>）只出现在 XML（§6.7）；
 *   向量以字面量字符串传入，由调用方经 PgVectorUtils 转换。
 * - 无 @Transactional（§6.11）；事务边界在 DocumentServiceImpl。
 * - 结果一律映射为 rag/model 的 record，不返回 Map<String,Object>（§6.6/§13）。
 */
@Mapper
public interface DocChunkMapper {

    int deleteByDoc(@Param("docId") String docId);

    /** 单条多行 INSERT（一次往返）；调用方保证 rows 非空 */
    int insertChunks(@Param("docId") String docId, @Param("docTitle") String docTitle,
                     @Param("sourcePath") String sourcePath, @Param("rows") List<ChunkRow> rows);

    int countByDoc(@Param("docId") String docId);

    List<DocSummary> listDocuments();

    List<DocChunkLine> listChunkLines(@Param("docId") String docId);

    List<VectorHit> searchByVector(@Param("vec") String vec, @Param("topK") int topK);

    /** 调用方保证 tokens 非空（<foreach> 空集合会生成非法 SQL） */
    List<KeywordHit> searchByKeyword(@Param("tokens") List<String> tokens, @Param("topK") int topK);
}
```

- [ ] **Step 4: 新建 `src/main/resources/mapper/DocChunkMapper.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<!--
  doc_chunk 读写。pgvector 语法集中在本文件（§6.7）：
  向量以文本字面量 "[0.1,...]" 传入，统一 CAST(... AS vector)；
  距离排序用 <=> 余弦距离操作符（HNSW 索引在此生效）。
  结果映射用位置式 <constructor>（select 列顺序 == record 组件顺序），
  不依赖 argNameBasedConstructorAutoMapping。
-->
<mapper namespace="com.lender.assistant.rag.mapper.DocChunkMapper">

    <resultMap id="docSummaryMap" type="com.lender.assistant.rag.model.DocSummary">
        <constructor>
            <arg column="doc_id" javaType="java.lang.String"/>
            <arg column="doc_title" javaType="java.lang.String"/>
            <arg column="chunk_count" javaType="int"/>
        </constructor>
    </resultMap>

    <resultMap id="chunkLineMap" type="com.lender.assistant.rag.model.DocChunkLine">
        <constructor>
            <arg column="doc_title" javaType="java.lang.String"/>
            <arg column="section_path" javaType="java.lang.String"/>
            <arg column="content" javaType="java.lang.String"/>
        </constructor>
    </resultMap>

    <resultMap id="vectorHitMap" type="com.lender.assistant.rag.model.VectorHit">
        <constructor>
            <arg column="id" javaType="long"/>
            <arg column="doc_id" javaType="java.lang.String"/>
            <arg column="doc_title" javaType="java.lang.String"/>
            <arg column="section_path" javaType="java.lang.String"/>
            <arg column="content" javaType="java.lang.String"/>
            <arg column="distance" javaType="double"/>
        </constructor>
    </resultMap>

    <resultMap id="keywordHitMap" type="com.lender.assistant.rag.model.KeywordHit">
        <constructor>
            <arg column="id" javaType="long"/>
            <arg column="doc_id" javaType="java.lang.String"/>
            <arg column="doc_title" javaType="java.lang.String"/>
            <arg column="section_path" javaType="java.lang.String"/>
            <arg column="content" javaType="java.lang.String"/>
            <arg column="hits" javaType="int"/>
        </constructor>
    </resultMap>

    <delete id="deleteByDoc">
        DELETE FROM doc_chunk WHERE doc_id = #{docId}
    </delete>

    <!-- 单条多行 INSERT：一次往返写完整文档（比逐行 BATCH 少 N-1 次网络往返）。
         与旧 DocumentStore 的循环 INSERT 语义等价，唯一区别是 SQL 条数 -->
    <insert id="insertChunks">
        INSERT INTO doc_chunk
            (doc_id, doc_title, source_path, section_path, chunk_index, content, char_count, embedding)
        VALUES
        <foreach collection="rows" item="r" separator=",">
            (#{docId}, #{docTitle}, #{sourcePath}, #{r.sectionPath}, #{r.index},
             #{r.content}, #{r.charCount}, CAST(#{r.embedding} AS vector))
        </foreach>
    </insert>

    <select id="countByDoc" resultType="int">
        SELECT COUNT(*) FROM doc_chunk WHERE doc_id = #{docId}
    </select>

    <select id="listDocuments" resultMap="docSummaryMap">
        SELECT doc_id, doc_title, COUNT(*) AS chunk_count
        FROM doc_chunk
        GROUP BY doc_id, doc_title
        ORDER BY doc_id
    </select>

    <select id="listChunkLines" resultMap="chunkLineMap">
        SELECT doc_title, section_path, content
        FROM doc_chunk
        WHERE doc_id = #{docId}
        ORDER BY chunk_index
    </select>

    <select id="searchByVector" resultMap="vectorHitMap">
        SELECT id, doc_id, doc_title, section_path, content,
               embedding &lt;=&gt; CAST(#{vec} AS vector) AS distance
        FROM doc_chunk
        ORDER BY embedding &lt;=&gt; CAST(#{vec} AS vector)
        LIMIT #{topK}
    </select>

    <!-- 分词后多词 OR 匹配，按命中词数 hits 降序；同分短 chunk 优先（char_count 升序）。
         旧 v1 整句 trigram 的教训见 SearchServiceImpl 注释。词值一律 #{} 参数化，无注入面 -->
    <select id="searchByKeyword" resultMap="keywordHitMap">
        SELECT id, doc_id, doc_title, section_path, content,
               (<foreach collection="tokens" item="t" separator=" + ">(CASE WHEN content LIKE CONCAT('%', #{t}, '%') THEN 1 ELSE 0 END)</foreach>) AS hits
        FROM doc_chunk
        WHERE <foreach collection="tokens" item="t" separator=" OR ">content LIKE CONCAT('%', #{t}, '%')</foreach>
        ORDER BY hits DESC, char_count ASC
        LIMIT #{topK}
    </select>

</mapper>
```

- [ ] **Step 5: 新建 `rag/service/DocumentService.java`**

```java
package com.lender.assistant.rag.service;

import com.lender.assistant.rag.model.ChunkRow;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.model.DocSummary;

import java.util.List;

/**
 * doc_chunk 的领域服务：rag 领域内文档读写的唯一入口。
 *
 * 为什么需要它（§1 依赖方向 controller → service → mapper）：
 * Controller 与跨领域消费者（LenderQueryTools、ExtractServiceImpl）不该直接触碰 Mapper，
 * 否则 SQL 细节会往上漏、跨领域会绕过 service 直连别人的数据层。跨领域只经本接口（service → service）。
 */
public interface DocumentService {

    /** 先删旧块再插新块：同一 docId 重复入库幂等；删+插必须原子（事务在实现方法上） */
    void replaceDocument(String docId, String docTitle, String sourcePath, List<ChunkRow> rows);

    int countByDoc(String docId);

    void deleteByDoc(String docId);

    List<DocSummary> listDocuments();

    /** 按 chunk 序拼全文（每块前置一行【章节】section_path）；文档不存在返回 null */
    DocContent loadDocContent(String docId);
}
```

- [ ] **Step 6: 新建 `rag/service/impl/DocumentServiceImpl.java`**

```java
package com.lender.assistant.rag.service.impl;

import com.lender.assistant.rag.mapper.DocChunkMapper;
import com.lender.assistant.rag.model.ChunkRow;
import com.lender.assistant.rag.model.DocChunkLine;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.model.DocSummary;
import com.lender.assistant.rag.service.DocumentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * doc_chunk 数据访问编排（原 rag/store/DocumentStore 的 MyBatis 化，§11）。
 *
 * 事务从 Store 迁到这里（§6.11：Mapper 上禁止 @Transactional）。
 * 事务内只有 DELETE + 单条多行 INSERT（§7：事务内禁止长耗时操作，解析/切分/向量化留在 Controller）。
 */
@Service
public class DocumentServiceImpl implements DocumentService {

    private final DocChunkMapper mapper;

    public DocumentServiceImpl(DocChunkMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceDocument(String docId, String docTitle, String sourcePath, List<ChunkRow> rows) {
        mapper.deleteByDoc(docId);
        if (rows.isEmpty()) {
            return;   // 空文档：删旧即完成，避免 <foreach> 生成空 VALUES（非法 SQL）
        }
        mapper.insertChunks(docId, docTitle, sourcePath, rows);
    }

    @Override
    public int countByDoc(String docId) {
        return mapper.countByDoc(docId);
    }

    @Override
    public void deleteByDoc(String docId) {
        mapper.deleteByDoc(docId);
    }

    @Override
    public List<DocSummary> listDocuments() {
        return mapper.listDocuments();
    }

    @Override
    public DocContent loadDocContent(String docId) {
        List<DocChunkLine> lines = mapper.listChunkLines(docId);
        if (lines.isEmpty()) {
            return null;
        }
        // 【章节】前缀拼接规则与旧 DocumentStore 逐字一致——抽取器靠它按章节分类接口路径。
        // 旧实现为取标题多发一条 SELECT ... LIMIT 1；同一 doc_id 的 doc_title 相同，取首行即可
        String text = lines.stream()
                .map(l -> "【章节】" + l.sectionPath() + "\n" + l.content())
                .collect(Collectors.joining("\n"));
        return new DocContent(lines.get(0).docTitle(), text);
    }
}
```

- [ ] **Step 7: 编译（新类尚无人调用，旧 `DocumentStore` 仍在，测试应仍全绿）**

```powershell
mvn -q test-compile
mvn -q test
```

Expected: `BUILD SUCCESS`，56 例全绿。

- [ ] **Step 8: 提交**

```powershell
git add src/main/java/com/lender/assistant/common/util src/main/java/com/lender/assistant/rag/model src/main/java/com/lender/assistant/rag/mapper src/main/resources/mapper/DocChunkMapper.xml src/main/java/com/lender/assistant/rag/service/DocumentService.java src/main/java/com/lender/assistant/rag/service/impl/DocumentServiceImpl.java
git commit -m "阶段6(part4): DocChunkMapper + XML + DocumentService 基建（rag 数据访问 MyBatis 化第一步）"
```

---

### Task B5: `rag` 调用方切到 `DocumentService`，删除 `DocumentStore`

**Files:**
- Modify: `src/main/java/com/lender/assistant/controller/DocumentAdminController.java`
- Modify: `src/main/java/com/lender/assistant/rag/service/impl/SearchServiceImpl.java`
- Modify: `src/main/java/com/lender/assistant/extract/service/impl/ExtractServiceImpl.java`
- Modify: `src/main/java/com/lender/assistant/tools/LenderQueryTools.java`
- Modify tests: `rag/service/SearchServiceTest`、`tools/LenderQueryToolsTest`、`rag/service/MockToolAnswerTest`、`extract/service/ExtractServiceTest`
- Delete: `src/main/java/com/lender/assistant/rag/store/DocumentStore.java`

> 顺序要求：**先改完全部调用方再删 `DocumentStore`**，否则中途编译不过。删完 `rag/store/` 整包为空，一并删除目录。

- [ ] **Step 1: 改写 `DocumentAdminController`**

```text
import：
- import com.lender.assistant.rag.store.DocumentStore;
+ import com.lender.assistant.common.util.PgVectorUtils;
+ import com.lender.assistant.rag.model.ChunkRow;
+ import com.lender.assistant.rag.model.DocSummary;
+ import com.lender.assistant.rag.service.DocumentService;
  （SearchService 的 import 已在 A1 改为 rag.service.SearchService）

字段与构造器：
  private final DocumentStore store;   →  private final DocumentService documentService;
  构造器参数 DocumentStore store        →  DocumentService documentService
  this.store = store;                  →  this.documentService = documentService;

upload()：
  List<DocumentStore.ChunkRow> rows = new ArrayList<>(chunks.size());
    →  List<ChunkRow> rows = new ArrayList<>(chunks.size());
  rows.add(new DocumentStore.ChunkRow(c.sectionPath(), c.index(), c.content(),
              c.charCount(), embeddingClient.embed(embedInput)));
    →  rows.add(new ChunkRow(c.sectionPath(), c.index(), c.content(), c.charCount(),
              PgVectorUtils.toLiteral(embeddingClient.embed(embedInput))));
       （新 ChunkRow 的 embedding 是 pgvector 文本字面量而非 float[]——§6.7 的载体变化）
  store.replaceDocument(docId, stripExtension(fileName), fileName, rows);
    →  documentService.replaceDocument(docId, stripExtension(fileName), fileName, rows);
  rows.stream().mapToInt(DocumentStore.ChunkRow::charCount)
    →  rows.stream().mapToInt(ChunkRow::charCount)

list()：
  public List<DocumentStore.DocSummary> list()  →  public List<DocSummary> list()
  return store.listDocuments();                 →  return documentService.listDocuments();

delete()：
  int before = store.countByDoc(docId);  →  int before = documentService.countByDoc(docId);
  store.deleteByDoc(docId);              →  documentService.deleteByDoc(docId);

（t0–t4 计时、响应 Map 键名、search()、topK()、stripExtension() 逐字保留）
```

- [ ] **Step 2: 改写 `SearchServiceImpl`**

```text
import：
- import com.lender.assistant.rag.store.DocumentStore;
+ import com.lender.assistant.common.util.PgVectorUtils;
+ import com.lender.assistant.rag.mapper.DocChunkMapper;
+ import com.lender.assistant.rag.model.KeywordHit;
+ import com.lender.assistant.rag.model.VectorHit;

字段与构造器：
  private final DocumentStore store;   →  private final DocChunkMapper docChunkMapper;
  构造器参数 DocumentStore store        →  DocChunkMapper docChunkMapper
  this.store = store;                  →  this.docChunkMapper = docChunkMapper;

search() 通道 1：
  List<DocumentStore.VectorHit> hits = store.searchByVector(queryVec, topK);
    →  List<VectorHit> hits = docChunkMapper.searchByVector(PgVectorUtils.toLiteral(queryVec), topK);
  for (DocumentStore.VectorHit hit : hits)   →  for (VectorHit hit : hits)

search() 通道 2：
  List<DocumentStore.KeywordHit> hits = store.searchByKeyword(tokens, topK);
    →  // 空 token 守卫下沉到 Service：MyBatis <foreach> 空集合会生成非法 SQL
       //（旧 DocumentStore 在方法内部处理 tokens.isEmpty()）
       List<KeywordHit> hits = tokens.isEmpty() ? List.of() : docChunkMapper.searchByKeyword(tokens, topK);
  DocumentStore.KeywordHit hit = hits.get(i);  →  KeywordHit hit = hits.get(i);

（K=60、maxDistance 阈值判断、base()/chunkId()、三模式排序逐字保留——RRF 行为是特征测试锁定对象）
```

- [ ] **Step 3: 改写 `ExtractServiceImpl`**

```text
import：
- import com.lender.assistant.rag.store.DocumentStore;
+ import com.lender.assistant.rag.model.DocContent;
+ import com.lender.assistant.rag.service.DocumentService;

字段与构造器：
  private final DocumentStore store;   →  private final DocumentService documentService;
  构造器参数 DocumentStore store        →  DocumentService documentService
  this.store = store;                  →  this.documentService = documentService;

extractFromDoc()：
  DocumentStore.DocContent doc = store.loadDocContent(docId);
    →  DocContent doc = documentService.loadDocContent(docId);
（空文档异常分支与 executor.execute 逐字保留；跨领域取数走 service → service，符合 §1）
```

- [ ] **Step 4: 改写 `LenderQueryTools`**

```text
import：
- import com.lender.assistant.rag.store.DocumentStore;
+ import com.lender.assistant.rag.model.DocSummary;
+ import com.lender.assistant.rag.service.DocumentService;

字段与构造器：
  private final DocumentStore store;   →  private final DocumentService documentService;
  构造器参数 DocumentStore store        →  DocumentService documentService
  this.store = store;                  →  this.documentService = documentService;

listIngestedDocs()：
  List<DocumentStore.DocSummary> docs = store.listDocuments();
    →  List<DocSummary> docs = documentService.listDocuments();
  DocumentStore.DocSummary d = docs.get(i);   →  DocSummary d = docs.get(i);
getDocChunkStats()：
  int n = store.countByDoc(docId);   →  int n = documentService.countByDoc(docId);
knownDocIds()：
  return store.listDocuments().stream().map(DocumentStore.DocSummary::docId).toList();
    →  return documentService.listDocuments().stream().map(DocSummary::docId).toList();
（两个 @Tool 的 name/description、格式化文案逐字保留——模型靠 description 决策调用）
```

- [ ] **Step 5: 删除 `DocumentStore` 与空包**

```text
删除：src/main/java/com/lender/assistant/rag/store/DocumentStore.java
删除目录：src/main/java/com/lender/assistant/rag/store/
```

- [ ] **Step 6: 改写 4 个测试类**

```text
rag/service/SearchServiceTest.java
- import com.lender.assistant.rag.store.DocumentStore;
+ import com.lender.assistant.rag.mapper.DocChunkMapper;
+ import com.lender.assistant.rag.model.KeywordHit;
+ import com.lender.assistant.rag.model.VectorHit;
+ import static org.mockito.ArgumentMatchers.anyString;
  第 32 行：private DocumentStore store;                  →  private DocChunkMapper mapper;
  第 38 行：store = mock(DocumentStore.class);            →  mapper = mock(DocChunkMapper.class);
  第 39 行：new SearchServiceImpl(embeddingClient, store, -1)
          →  new SearchServiceImpl(embeddingClient, mapper, -1);
  vec 辅助：private DocumentStore.VectorHit vec(...) { return new DocumentStore.VectorHit(...); }
          →  private VectorHit vec(...) { return new VectorHit(...); }
  kw  辅助：private DocumentStore.KeywordHit kw(...) { return new DocumentStore.KeywordHit(...); }
          →  private KeywordHit kw(...) { return new KeywordHit(...); }
  stub/verify 全部把 store → mapper：
    when(store.searchByVector(any(), anyInt()))     →  when(mapper.searchByVector(anyString(), anyInt()))
      （首参现在是向量字面量字符串；服务总传非空，故用 anyString() 收紧）
    when(store.searchByKeyword(anyList(), anyInt()))→  when(mapper.searchByKeyword(anyList(), anyInt()))
    verify(store, never()).searchByKeyword(any(), anyInt())
      →  verify(mapper, never()).searchByKeyword(any(), anyInt())
    verify(store).searchByKeyword(captor.capture(), anyInt())
      →  verify(mapper).searchByKeyword(captor.capture(), anyInt())
  （RRF 期望值 0.03252 / 0.01639 / 0.01613、token 过滤期望 [逾期, 期第] 逐字保留）

tools/LenderQueryToolsTest.java
- import com.lender.assistant.rag.store.DocumentStore;
+ import com.lender.assistant.rag.model.DocSummary;
+ import com.lender.assistant.rag.service.DocumentService;
  第 19 行：private DocumentStore store;                        →  private DocumentService documentService;
  第 24 行：store = mock(DocumentStore.class);                  →  documentService = mock(DocumentService.class);
  第 25 行：tools = new LenderQueryTools(store);                →  tools = new LenderQueryTools(documentService);
  new DocumentStore.DocSummary(...) → new DocSummary(...)（共 3 处）
  when(store.listDocuments())  → when(documentService.listDocuments())（4 处）
  when(store.countByDoc("ruijin_api_v1")) / when(store.countByDoc("nope"))
    → when(documentService.countByDoc(...))
  第 62 行：verify(store).countByDoc("ruijin_api_v1") → verify(documentService).countByDoc("ruijin_api_v1")
  （5 个断言逐字保留）

rag/service/MockToolAnswerTest.java
- import com.lender.assistant.rag.store.DocumentStore;
+ import com.lender.assistant.rag.model.DocSummary;
+ import com.lender.assistant.rag.service.DocumentService;
  第 22 行：private DocumentStore store;      →  private DocumentService documentService;
  第 27 行：store = mock(DocumentStore.class);→  documentService = mock(DocumentService.class);
  第 28-29 行：when(store.listDocuments()).thenReturn(List.of(new DocumentStore.DocSummary(...)))
            →  when(documentService.listDocuments()).thenReturn(List.of(new DocSummary(...)))
  第 30 行：when(store.countByDoc("ruijin_api_v1")).thenReturn(5);
          →  when(documentService.countByDoc("ruijin_api_v1")).thenReturn(5);
  第 31 行：new MockAnswerGenerator(new LenderQueryTools(store), new ToolIntentRouter())
          →  new MockAnswerGenerator(new LenderQueryTools(documentService), new ToolIntentRouter());
  第 52/62/72/73 行：verify(store) → verify(documentService)
  （3 个断言逐字保留）

extract/service/ExtractServiceTest.java
- import com.lender.assistant.rag.store.DocumentStore;
+ import com.lender.assistant.rag.model.DocContent;
+ import com.lender.assistant.rag.service.DocumentService;
  第 28 行：private DocumentStore store;                          →  private DocumentService documentService;
  第 34 行：store = mock(DocumentStore.class);                    →  documentService = mock(DocumentService.class);
  第 40 行：service = new ExtractServiceImpl(generator, store, executor);
          →  service = new ExtractServiceImpl(generator, documentService, executor);
  第 46-48 行：when(store.loadDocContent("doc1")).thenReturn(new DocumentStore.DocContent(...))
            →  when(documentService.loadDocContent("doc1")).thenReturn(new DocContent(...))
  第 80 行：when(store.loadDocContent("nope")).thenReturn(null);
          →  when(documentService.loadDocContent("nope")).thenReturn(null);
  （3 个断言逐字保留）
```

- [ ] **Step 7: 编译并跑全量测试**

```powershell
Remove-Item -Recurse -Force target/test-classes
mvn -q test
```

Expected: `BUILD SUCCESS`，56 例全绿。`SearchServiceTest` 必须是重点观察对象——它锁定 RRF 行为，若 RRF 期望值报警说明迁移改坏了融合逻辑。

- [ ] **Step 8: 提交**

```powershell
git add -u src/main/java/com/lender/assistant/rag/store src/main/java/com/lender/assistant/controller/DocumentAdminController.java src/main/java/com/lender/assistant/rag/service/impl/SearchServiceImpl.java src/main/java/com/lender/assistant/extract/service/impl/ExtractServiceImpl.java src/main/java/com/lender/assistant/tools/LenderQueryTools.java src/test/java/com/lender/assistant/rag src/test/java/com/lender/assistant/tools src/test/java/com/lender/assistant/extract
git commit -m "阶段6(part5): rag 数据访问切到 DocumentService/DocChunkMapper，删除 DocumentStore"
```

---

### Task B6: `extract` 数据访问迁移 —— `FieldMappingMapper`，删除 `FieldMappingStore`

**Files:**
- Create: `src/main/java/com/lender/assistant/extract/model/FieldMappingRow.java`
- Create: `src/main/java/com/lender/assistant/extract/model/FieldMappingRaw.java`
- Create: `src/main/java/com/lender/assistant/extract/mapper/FieldMappingMapper.java`
- Create: `src/main/resources/mapper/FieldMappingMapper.xml`
- Modify: `src/main/java/com/lender/assistant/extract/service/ExtractService.java`（接口扩展 4 个方法）
- Modify: `src/main/java/com/lender/assistant/extract/service/impl/ExtractServiceImpl.java`（实现扩展）
- Modify: `src/main/java/com/lender/assistant/controller/ExtractController.java`
- Modify: `src/main/java/com/lender/assistant/draft/service/impl/DraftServiceImpl.java`
- Modify tests: `extract/service/ExtractServiceTest`（3 例 → 7 或 8 例，见 Step 10）、`draft/service/DraftServiceTest`
- Delete: `src/main/java/com/lender/assistant/extract/FieldMappingStore.java`
- Delete: `src/test/java/com/lender/assistant/extract/FieldMappingStoreTest.java`

> **JSON 契约不变**：`FieldMappingRow` 的组件名（`id` / `docId` / `internalFieldId` / `lenderFieldName` / `lenderValue` / `sourceQuote` / `confidence` / `status`）与旧 `FieldMappingStore.listMappings` 返回 Map 的键名逐字相同，`GET /api/extract/{docId}` 响应结构不变，**前端 `index.html` 无需改动**。旧代码里 `null → ""` 的兜底下沉到 XML 的 `COALESCE`。

- [ ] **Step 1: 新建 `extract/model/FieldMappingRow.java`（GET 列表行，契约载体）**

```java
package com.lender.assistant.extract.model;

/**
 * field_mapping 查询行：GET /api/extract/{docId} 的原样返回元素。
 *
 * 组件名与旧 FieldMappingStore.listMappings 的 Map 键名逐字相同——这是刻意的，
 * 保证 JSON 契约（前端渲染映射表）在迁移后零变化。
 * lenderFieldName / lenderValue / sourceQuote 的 null→"" 兜底由 XML 的 COALESCE 完成。
 */
public record FieldMappingRow(long id, String docId, String internalFieldId, String lenderFieldName,
                             String lenderValue, String sourceQuote, double confidence, String status) {
}
```

- [ ] **Step 2: 新建 `extract/model/FieldMappingRaw.java`（一次写入/取数的原子载体）**

```java
package com.lender.assistant.extract.model;

/**
 * field_mapping 的原始行（无内部字段名）：两个用途共用同一 record —
 * - 写入：saveMappings 的批量 upsert 参数（docId + 抽取出的 5 个字段）；
 * - 取数：草稿生成只为拿字段值，内部字段名由 Service 用 InternalFieldCatalog 补齐
 *   （§6.11：Mapper 只取数，这类业务判定留在 Service）。
 * confidence 为 primitive：DB 列为 null 时 JDBC 返回 0.0，与旧实现一致。
 */
public record FieldMappingRaw(String docId, String internalFieldId, String lenderFieldName,
                              String lenderValue, String sourceQuote, double confidence) {
}
```

- [ ] **Step 3: 新建 `extract/mapper/FieldMappingMapper.java`**

```java
package com.lender.assistant.extract.mapper;

import com.lender.assistant.extract.model.FieldMappingRaw;
import com.lender.assistant.extract.model.FieldMappingRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * field_mapping 表的唯一读写落点（§6.1）。
 *
 * upsert 的"只覆盖 PENDING 行"与"清理只限 PENDING"两条守门落在 XML（§6.8/§6.11）；
 * 清 stale + upsert 的原子性由 ExtractServiceImpl.saveMappings 的 @Transactional 保证。
 * confirm 的两分支（CORRECTED / CONFIRMED）在 Service 判断，此处拆成两条 SQL。
 */
@Mapper
public interface FieldMappingMapper {

    int deleteStalePending(@Param("docId") String docId);

    /** 单条多行 upsert（一次往返）；调用方保证 raws 非空 */
    int upsertAll(@Param("raws") List<FieldMappingRaw> raws);

    List<FieldMappingRow> listRows(@Param("docId") String docId);

    List<FieldMappingRaw> listForDraft(@Param("docId") String docId);

    void confirmCorrected(@Param("docId") String docId, @Param("internalFieldId") String internalFieldId,
                          @Param("correctedFieldName") String correctedFieldName,
                          @Param("correctedValue") String correctedValue);

    void confirmAsIs(@Param("docId") String docId, @Param("internalFieldId") String internalFieldId);
}
```

- [ ] **Step 4: 新建 `src/main/resources/mapper/FieldMappingMapper.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<!--
  field_mapping 读写。两条人工成果保护规则必须留在 SQL 里（§6.8）：
  1) 清理只针对 PENDING 行 —— CONFIRMED/CORRECTED 的人工成果不被重抽冲掉；
  2) upsert 的 ON CONFLICT ... WHERE field_mapping.status = 'PENDING' —— 人工已确认行不被覆盖。
  null → "" 的响应兜底下沉到 COALESCE（旧实现写在 Java RowMapper 里）。
  结果映射用位置式 <constructor>（select 列顺序 == record 组件顺序）。
-->
<mapper namespace="com.lender.assistant.extract.mapper.FieldMappingMapper">

    <resultMap id="rowMap" type="com.lender.assistant.extract.model.FieldMappingRow">
        <constructor>
            <arg column="id" javaType="long"/>
            <arg column="doc_id" javaType="java.lang.String"/>
            <arg column="internal_field_id" javaType="java.lang.String"/>
            <arg column="lender_field_name" javaType="java.lang.String"/>
            <arg column="lender_value" javaType="java.lang.String"/>
            <arg column="source_quote" javaType="java.lang.String"/>
            <arg column="confidence" javaType="double"/>
            <arg column="status" javaType="java.lang.String"/>
        </constructor>
    </resultMap>

    <resultMap id="rawMap" type="com.lender.assistant.extract.model.FieldMappingRaw">
        <constructor>
            <arg column="doc_id" javaType="java.lang.String"/>
            <arg column="internal_field_id" javaType="java.lang.String"/>
            <arg column="lender_field_name" javaType="java.lang.String"/>
            <arg column="lender_value" javaType="java.lang.String"/>
            <arg column="source_quote" javaType="java.lang.String"/>
            <arg column="confidence" javaType="double"/>
        </constructor>
    </resultMap>

    <delete id="deleteStalePending">
        DELETE FROM field_mapping WHERE doc_id = #{docId} AND status = 'PENDING'
    </delete>

    <insert id="upsertAll">
        INSERT INTO field_mapping
            (doc_id, internal_field_id, lender_field_name, lender_value, source_quote, confidence, status)
        VALUES
        <foreach collection="raws" item="r" separator=",">
            (#{r.docId}, #{r.internalFieldId}, #{r.lenderFieldName}, #{r.lenderValue},
             #{r.sourceQuote}, #{r.confidence}, 'PENDING')
        </foreach>
        ON CONFLICT (doc_id, internal_field_id) DO UPDATE
            SET lender_field_name = EXCLUDED.lender_field_name,
                lender_value      = EXCLUDED.lender_value,
                source_quote      = EXCLUDED.source_quote,
                confidence        = EXCLUDED.confidence,
                status            = 'PENDING'
            WHERE field_mapping.status = 'PENDING'
    </insert>

    <select id="listRows" resultMap="rowMap">
        SELECT id, doc_id, internal_field_id,
               COALESCE(lender_field_name, '') AS lender_field_name,
               COALESCE(lender_value, '')      AS lender_value,
               COALESCE(source_quote, '')      AS source_quote,
               confidence, status
        FROM field_mapping
        WHERE doc_id = #{docId}
        ORDER BY internal_field_id
    </select>

    <select id="listForDraft" resultMap="rawMap">
        SELECT doc_id, internal_field_id, lender_field_name, lender_value, source_quote, confidence
        FROM field_mapping
        WHERE doc_id = #{docId}
        ORDER BY internal_field_id
    </select>

    <update id="confirmCorrected">
        UPDATE field_mapping
        SET lender_value      = COALESCE(#{correctedValue}, lender_value),
            lender_field_name = COALESCE(#{correctedFieldName}, lender_field_name),
            status            = 'CORRECTED'
        WHERE doc_id = #{docId} AND internal_field_id = #{internalFieldId}
    </update>

    <update id="confirmAsIs">
        UPDATE field_mapping SET status = 'CONFIRMED'
        WHERE doc_id = #{docId} AND internal_field_id = #{internalFieldId}
    </update>

</mapper>
```

- [ ] **Step 5: 扩展 `extract/service/ExtractService.java` 接口**

整文件替换为：

```java
package com.lender.assistant.extract.service;

import com.lender.assistant.extract.model.FieldMapping;
import com.lender.assistant.extract.model.FieldMappingRow;

import java.util.List;

/**
 * 结构化抽取编排 + field_mapping 领域服务。
 *
 * 为什么把"映射表读写"也收到这里：field_mapping 是 extract 领域的私有数据，
 * Controller 与 draft 领域（草稿生成）都只经本接口访问它（§1：跨领域只允许 service → service），
 * 从而 DocumentStore / FieldMappingStore 这类直连数据层的类从调用方视野里彻底消失。
 */
public interface ExtractService {

    /** @throws IllegalArgumentException 文档不存在或未入库 */
    List<FieldMapping> extractFromDoc(String docId);

    /** 落库：先清该文档 stale PENDING 再批量 upsert（只覆盖 PENDING 行），返回写入条数 */
    int saveMappings(String docId, List<FieldMapping> mappings);

    /** 某文档完整映射行（含状态），供 GET /api/extract/{docId} */
    List<FieldMappingRow> listMappings(String docId);

    /** 类型化查询（内部字段名由字段目录补全），供草稿生成 */
    List<FieldMapping> listFieldMappings(String docId);

    /** 人工确认/修正：correctedValue 非空 → CORRECTED，否则 → CONFIRMED */
    void confirm(String docId, String internalFieldId, String correctedFieldName, String correctedValue);
}
```

- [ ] **Step 6: 扩展 `extract/service/impl/ExtractServiceImpl.java`**

整文件替换为（`extractFromDoc` 保持 A4/B5 后的形态，新增 4 个方法）：

```java
package com.lender.assistant.extract.service.impl;

import com.lender.assistant.extract.mapper.FieldMappingMapper;
import com.lender.assistant.extract.model.FieldMapping;
import com.lender.assistant.extract.model.FieldMappingRaw;
import com.lender.assistant.extract.model.FieldMappingRow;
import com.lender.assistant.extract.model.InternalField;
import com.lender.assistant.extract.service.ExtractService;
import com.lender.assistant.extract.service.InternalFieldCatalog;
import com.lender.assistant.rag.model.DocContent;
import com.lender.assistant.rag.service.DocumentService;
import com.lender.assistant.resilience.ResilientLlmExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化抽取编排 + 映射表读写（原 extract/ExtractService 与 extract/FieldMappingStore 合并，§11）。
 *
 * 抽取是**非流式**一锤子调用（JSON 进 JSON 出），allowRetry=true——
 * 失败重试不影响用户侧一致性；问答 SSE 则禁止重试（token 已推给用户）。
 * 事务从 Store 迁到 saveMappings（§6.11），覆盖"清 stale + upsert"两步的原子性。
 */
@Service
public class ExtractServiceImpl implements ExtractService {

    private final ExtractGenerator generator;
    private final DocumentService documentService;
    private final FieldMappingMapper mapper;
    private final ResilientLlmExecutor executor;

    public ExtractServiceImpl(ExtractGenerator generator, DocumentService documentService,
                              FieldMappingMapper mapper, ResilientLlmExecutor executor) {
        this.generator = generator;
        this.documentService = documentService;
        this.mapper = mapper;
        this.executor = executor;
    }

    @Override
    public List<FieldMapping> extractFromDoc(String docId) {
        DocContent doc = documentService.loadDocContent(docId);
        if (doc == null || doc.text().isBlank()) {
            throw new IllegalArgumentException("文档不存在或未入库: " + docId);
        }
        return executor.execute("extract", generator.name(),
                () -> generator.extract(doc.docTitle(), doc.text()), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveMappings(String docId, List<FieldMapping> mappings) {
        // 先清掉本次没再抽出的 PENDING 旧映射（规则收紧/文档更新后消失的字段不留脏数据）；
        // 人工成果（CONFIRMED/CORRECTED）不受影响——DELETE 只限 PENDING，
        // upsert 的 WHERE status='PENDING' 再守一道。两步必须原子。
        mapper.deleteStalePending(docId);
        if (mappings.isEmpty()) {
            return 0;   // 空映射：清 stale 即完成，避免 <foreach> 生成空 VALUES
        }
        List<FieldMappingRaw> raws = new ArrayList<>(mappings.size());
        for (FieldMapping m : mappings) {
            raws.add(new FieldMappingRaw(docId, m.internalFieldId(), m.lenderFieldName(),
                    m.lenderValue(), m.sourceQuote(), m.confidence()));
        }
        return mapper.upsertAll(raws);
    }

    @Override
    public List<FieldMappingRow> listMappings(String docId) {
        return mapper.listRows(docId);
    }

    @Override
    public List<FieldMapping> listFieldMappings(String docId) {
        return mapper.listForDraft(docId).stream()
                .map(raw -> new FieldMapping(raw.internalFieldId(), internalFieldName(raw.internalFieldId()),
                        raw.lenderFieldName(), raw.lenderValue(), raw.sourceQuote(), raw.confidence()))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String docId, String internalFieldId, String correctedFieldName, String correctedValue) {
        if (correctedValue != null && !correctedValue.isBlank()) {
            mapper.confirmCorrected(docId, internalFieldId, correctedFieldName, correctedValue);
        } else {
            mapper.confirmAsIs(docId, internalFieldId);
        }
    }

    /** 字段目录查不到时回退为 id 本身——与旧 FieldMappingStore.listFieldMappings 行为一致 */
    private static String internalFieldName(String internalFieldId) {
        InternalField field = InternalFieldCatalog.byId(internalFieldId);
        return field == null ? internalFieldId : field.name();
    }
}
```

- [ ] **Step 7: 改写 `ExtractController`（`store` → `extractService`）**

```text
import：
- import com.lender.assistant.extract.FieldMappingStore;   （删除）
  （A4 已把 ExtractService / InternalFieldCatalog 的 import 改到 extract.service）

字段：
  private final FieldMappingStore store;   →  删除该字段与构造器参数
构造器：
  public ExtractController(ExtractService extractService, FieldMappingStore store)
    →  public ExtractController(ExtractService extractService)
  this.store = store;                      →  删除

方法内调用：
  extract()：store.saveMappings(docId, mappings);  →  extractService.saveMappings(docId, mappings);
  list()：   store.listMappings(docId)             →  extractService.listMappings(docId)
  confirm()：store.confirm(docId, req.internalFieldId(), req.lenderFieldName(), req.lenderValue())
             →  extractService.confirm(docId, req.internalFieldId(), req.lenderFieldName(), req.lenderValue())

（响应 Map 键名、try/catch 降级、fields() 逐字保留；list() 的 mappings 现在是 List<FieldMappingRow>，
 JSON 键名与旧 Map 相同，响应结构不变）
```

- [ ] **Step 8: 改写 `DraftServiceImpl`（`FieldMappingStore` → `ExtractService`）**

```text
import：
- import com.lender.assistant.extract.FieldMappingStore;
+ import com.lender.assistant.extract.service.ExtractService;

字段与构造器：
  private final FieldMappingStore store;   →  private final ExtractService extractService;
  构造器参数 FieldMappingStore store        →  ExtractService extractService
  this.store = store;                      →  this.extractService = extractService;

generate()：
  List<FieldMapping> mappings = store.listFieldMappings(docId);
    →  List<FieldMapping> mappings = extractService.listFieldMappings(docId);
（空映射异常文案、REVIEW_NOTICE、executor.execute 逐字保留；跨领域 draft → extract.service，符合 §1）
```

- [ ] **Step 9: 删除 `FieldMappingStore` 与其旧测试**

```text
删除：src/main/java/com/lender/assistant/extract/FieldMappingStore.java
删除：src/test/java/com/lender/assistant/extract/FieldMappingStoreTest.java
（后者的 4 例覆盖并入 ExtractServiceTest，见 Step 10）
```

- [ ] **Step 10: 改 `ExtractServiceTest`（3 例 → 7 例）**

在 B5 已改好的基础上继续：

```text
import：
+ import com.lender.assistant.extract.mapper.FieldMappingMapper;
+ import com.lender.assistant.extract.model.FieldMappingRaw;
+ import com.lender.assistant.extract.model.FieldMappingRow;
+ import java.util.ArrayList;   （captor 断言用）

字段与 setUp：
  private FieldMappingMapper mapper;
  mapper = mock(FieldMappingMapper.class);
  service = new ExtractServiceImpl(generator, documentService, mapper, executor);
  （原 3 例 orchestratesLoadAndExtract / generatorFailureIsLoggedAndRethrown /
    missingDocGivesReadableError 逐字保留，断言不变）
```

新增 4 例（等价承接原 `FieldMappingStoreTest` 的行为契约）：

```java
    private FieldMapping sampleMapping() {
        return new FieldMapping("gateway_url", "生产网关地址", "网关地址",
                "https://fake-ruijin.example.com/openapi", "网关地址 https://fake-ruijin.example.com/openapi", 0.9);
    }

    @Test
    @DisplayName("落库：先清 stale PENDING 再批量 upsert，两步顺序与参数正确")
    void saveMappingsCleansStaleThenUpserts() {
        when(mapper.deleteStalePending("doc1")).thenReturn(0);
        when(mapper.upsertAll(anyList())).thenReturn(1);

        int n = service.saveMappings("doc1", List.of(sampleMapping()));

        assertEquals(1, n);
        // 顺序断言：必须先清理再覆盖，否则消失的字段会残留成脏数据
        InOrder inOrder = inOrder(mapper);
        inOrder.verify(mapper).deleteStalePending("doc1");
        inOrder.verify(mapper).upsertAll(anyList());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FieldMappingRaw>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(mapper).upsertAll(captor.capture());
        FieldMappingRaw raw = captor.getValue().get(0);
        assertEquals("doc1", raw.docId());
        assertEquals("gateway_url", raw.internalFieldId());
        assertEquals(0.9, raw.confidence(), 1e-9);
    }

    @Test
    @DisplayName("落库：空映射表只清 stale，不触发 upsert（避免空 VALUES）")
    void saveMappingsEmptySkipsUpsert() {
        service.saveMappings("doc1", List.of());

        verify(mapper).deleteStalePending("doc1");
        verify(mapper, never()).upsertAll(anyList());
    }

    @Test
    @DisplayName("确认/修正：带修正值走 CORRECTED，空修正值走 CONFIRMED（人工成果状态机）")
    void confirmBranchesByCorrectedValue() {
        service.confirm("doc1", "gateway_url", "新标签", "https://new.example.com");
        verify(mapper).confirmCorrected("doc1", "gateway_url", "新标签", "https://new.example.com");
        verify(mapper, never()).confirmAsIs(anyString(), anyString());

        service.confirm("doc1", "gateway_url", null, null);
        verify(mapper).confirmAsIs("doc1", "gateway_url");
    }

    @Test
    @DisplayName("类型化查询：内部字段名由字段目录补全，未知 id 回退为 id 本身")
    void listFieldMappingsFillsNameFromCatalog() {
        when(mapper.listForDraft("doc1")).thenReturn(List.of(
                new FieldMappingRaw("doc1", "gateway_url", "网关地址", "https://x", "网关地址 https://x", 0.9),
                new FieldMappingRaw("doc1", "no_such_field", "x", "y", "z", 0.7)));

        List<FieldMapping> result = service.listFieldMappings("doc1");

        assertEquals("生产网关地址", result.get(0).internalFieldName(), "目录里的字段必须补出中文名");
        assertEquals("no_such_field", result.get(1).internalFieldName(), "目录查不到时回退为 id，不丢数据");
    }
```

同时新增一条覆盖 `listMappings` 直通（与上面 4 条合并计数见下方说明）：

```java
    @Test
    @DisplayName("映射列表：原样返回带状态的行（JSON 契约载体）")
    void listMappingsReturnsRowsAsIs() {
        when(mapper.listRows("doc1")).thenReturn(List.of(
                new FieldMappingRow(1L, "doc1", "gateway_url", "", "https://x", "", 0.9, "PENDING")));

        List<FieldMappingRow> rows = service.listMappings("doc1");

        assertEquals(1, rows.size());
        assertEquals("PENDING", rows.get(0).status());
        assertEquals("gateway_url", rows.get(0).internalFieldId());
    }
```

> **测试计数**：删除 `FieldMappingStoreTest`（4 例）后为 52 例，`ExtractServiceTest` 由 3 例扩为 8 例（+5），合计 **57 例**。多出的 1 例是把原 4 例中"upsert 形态"与"清理 stale"两条合成 1 例、另拆出"空映射守卫"所致——**测试数只增不减**，符合 §12.1（数量下降必须说明，此处未下降）。

```text
若严格保持 56 例：删掉上面 listMappingsReturnsRowsAsIs 一例即可（其覆盖由真实库冒烟 + XML 静态断言承担）。
执行者二选一，最终数量在提交信息里写明。
```

- [ ] **Step 11: 改 `DraftServiceTest`**

```text
import：
- import com.lender.assistant.extract.FieldMappingStore;
+ import com.lender.assistant.extract.service.ExtractService;
  （B3 已把 JdbcTemplate 换成 LlmCallLogMapper；A4 已把 FieldMapping 改到 extract.model）

第 26 行：private FieldMappingStore store;   →  private ExtractService extractService;
第 33 行：store = mock(FieldMappingStore.class); →  extractService = mock(ExtractService.class);
第 38 行：service = new DraftServiceImpl(generator, store, executor);
        →  service = new DraftServiceImpl(generator, extractService, executor);
第 43 行：when(store.listFieldMappings("doc1")).thenReturn(List.of(...))
        →  when(extractService.listFieldMappings("doc1")).thenReturn(List.of(...))
第 51 行：when(store.listFieldMappings("doc_none")).thenReturn(List.of());
        →  when(extractService.listFieldMappings("doc_none")).thenReturn(List.of());
（3 个断言逐字保留；第 71 行 DraftService.DraftResult 写法保持有效）
```

- [ ] **Step 12: 编译并跑全量测试**

```powershell
Remove-Item -Recurse -Force target/test-classes
mvn -q test
```

Expected: `BUILD SUCCESS`，56 或 57 例全绿（取决于 Step 10 执行者是否保留 `listMappingsReturnsRowsAsIs`）。

- [ ] **Step 13: 提交**

```powershell
git add -u src/main/java/com/lender/assistant/extract src/test/java/com/lender/assistant/extract src/main/java/com/lender/assistant/controller/ExtractController.java src/main/java/com/lender/assistant/draft/service/impl/DraftServiceImpl.java src/test/java/com/lender/assistant/draft/service/DraftServiceTest.java
git commit -m "阶段6(part6): extract 映射表切到 FieldMappingMapper/ExtractService，删除 FieldMappingStore"
```

---

### Task B7: 全量验证 + 真实库冒烟 + §11 收尾

**Files:**
- Modify: `AGENTS.md`（§11 两张表的 checkbox 勾选）

- [ ] **Step 1: 全量测试（最终基线）**

```powershell
Remove-Item -Recurse -Force target/test-classes
mvn -q test
```

Expected: `BUILD SUCCESS`，测试数 ≥ 56 且全绿。若数量变动，记录在提交信息里并说明原因。

- [ ] **Step 2: 启动应用做真实库冒烟（需要 Docker，人工执行）**

```powershell
docker compose up -d
Start-Sleep -Seconds 20      # 等 pg healthcheck 通过 + 应用起来
curl.exe http://localhost:8080/health
```

依次手工验证（每一步都应返回 2xx 且结构如预期，且 `llm_call_log` / `doc_chunk` / `field_mapping` 三表有真实数据）：

```text
1. curl.exe "http://localhost:8080/api/admin/documents"                 → [] 或已有文档清单
2. curl.exe -F "file=@docs/sample_docs/瑞金租赁_资金对接接口文档_v1.0.docx" -F "docId=ruijin_api_v1" http://localhost:8080/api/admin/documents
        → {"docId":"ruijin_api_v1","chunkCount":N,...}（N>0，验证 insertChunks 多行 INSERT）
   （若样例文档路径不同，用 docs/gen_sample_docs.py 生成后再传）
3. curl.exe "http://localhost:8080/api/admin/documents"                 → 含 ruijin_api_v1，chunkCount 与上一步一致
4. curl.exe -X POST http://localhost:8080/api/extract/ruijin_api_v1     → {"count":M,"mappings":[...]}（验证 upsertAll）
5. curl.exe "http://localhost:8080/api/extract/ruijin_api_v1"           → mappings 含 id/docId/status 全部键（验证 JSON 契约未变）
6. curl.exe -X POST http://localhost:8080/api/draft/ruijin_api_v1       → 含 reviewNotice 与 draft（验证 listForDraft + 目录补名）
7. curl.exe -X POST -H "Content-Type: application/json" -d "{\"question\":\"逾期阶段怎么划分\",\"topK\":4}" http://localhost:8080/api/qa
        → SSE 事件流（验证向量通道 CAST(... AS vector) 与关键词通道 <foreach> 都能跑）
8. docker exec -i lender-pg psql -U postgres -d lender_assistant -c "SELECT count(*) FROM llm_call_log"
        → >0（验证 LlmCallLogMapper 落库）
```

Expected: 全部通过；若有任一失败，**先修复再进入下一步**（这是唯一的真实库验证关口）。

- [ ] **Step 3: 关闭服务**

```powershell
docker compose down
```

- [ ] **Step 4: 勾选 `AGENTS.md` §11 两张表**

```text
数据访问迁移三条 checkbox 全部改为 [x]：
- [x] `rag/store/DocumentStore`（JdbcTemplate）→ `rag/mapper/DocChunkMapper`（+ `resources/mapper/DocChunkMapper.xml`）
- [x] `extract/FieldMappingStore`（实际在 extract 根，不在 extract/store）→ `extract/mapper/FieldMappingMapper`（+ XML）
- [x] `resilience/LlmCallLogger`（JdbcTemplate 留痕）→ 写库部分落到 `resilience/mapper/LlmCallLogMapper`（+ XML），LlmCallLogger 保留为调用侧装饰器

包结构迁移表：逐行确认已达目标位置，全部勾选；
把「迁移完成后删除顶层 controller/、service/ 空包，此后禁止再创建」改为 [x]（A5/A6 已完成）。
```

- [ ] **Step 5: 提交**

```powershell
git add AGENTS.md
git commit -m "docs(规范): AGENTS.md §11 数据访问迁移与包结构迁移清单勾选完成"
```

---

## 全量验收口径（本计划完成的定义）

1. `src/main/java` 下**不存在**顶层 `controller/` / `service/` / `rag/store/` / `rag/qa/` / `rag/eval/` 包；
   领域分包为 `rag/ extract/ draft/ ops/`，横向包为 `tools/ resilience/ common/`。
2. 五处编排型 Service（`SearchService` / `QaService` / `EvalService` / `ExtractService` / `DraftService`）
   均为「接口 + `service/impl/XxxServiceImpl`」，新增 `DocumentService` 同形态。
3. **全仓库（含 `src/main` 与 `src/test`）不再出现 `JdbcTemplate`**，三处数据访问全部经 Mapper + XML；
   Mapper 上无 `@Transactional`，事务只在 Service（`replaceDocument` / `saveMappings` / `confirm`）。
4. SQL 全部走 `#{}` 占位（`Grep` 搜 `${` 在 `resources/mapper/` 下零命中）、无 `SELECT *`、
   pgvector 语法只出现在 `DocChunkMapper.xml`。
5. `mvn test` 全绿，测试数 ≥ 56；`GET /api/extract/{docId}` 与前端 `index.html` 交互零改动。
6. `docs/schema.sql` **未改动**（本次无 schema 变更，符合 §6.9 的反向要求）。

---

## 明确排除（本计划不做，避免范围蔓延）

以下条目属 `AGENTS.md` §11「代码治理」，与「包结构 + 数据访问」是两个独立主题，**本次不动**：

- 场景名魔法值 `"qa"` / `"extract"` / `"draft"`、超时秒数、置信度 `0.9` / `0.7` 归 `common/constant/`。
- controller 返回 `Map<String,Object>` 手拼键名收敛为 `record`
  （含 `DocumentAdminController.upload()` / `QaController.qa()` 的请求体 `Map`）。
- `QaServiceImpl` 的 `Executors.newFixedThreadPool(8, ...)` → `ThreadPoolExecutor` 显式参数（§8.1）。
- `SearchService.search()` 返回 `List<Map<String,Object>>`：§13 禁止跨层 Map 契约，
  但 §11 明确豁免存量（本计划保留接口签名不变，正是为了不把 RRF 特征测试卷进来）。
- ingest 流水线（parse → split → embed）留在 Controller：§1 分层要求下它应下沉到
  `DocumentService`，但下沉后会把"模型调用"卷进领域 service 的编排层，属独立重构主题。

---

## Self-Review 记录（writing-plans 要求的三项自检）

**1. spec 覆盖**：逐条对 `AGENTS.md`。
- §0.2/§0.3 目标包结构 → Task A1–A6 搬迁 + A7 同步规范；`ops` / `rag/model` / `resilience/model` / `common/util` 已在 A7 写进 §0.2 结构图。
- §1 依赖方向 + 接口化 → A1–A4 抽接口；B4 新增 `DocumentService` 让 Controller/跨领域不碰 Mapper。
- §6.1–§6.11 → B1–B6：SQL 只在 mapper、`#{}`、显式列名、手写 LIMIT/OFFSET（无分页插件）、record 映射、pgvector 只在 XML、upsert、`@Transactional` 移出 Mapper。
- §7 事务 → `DocumentServiceImpl.replaceDocument` / `ExtractServiceImpl.saveMappings` / `confirm`，事务内无模型调用。
- §10 单测 → 全部改测试的 task 都写明"改签名必须同步 src/test"；B6 承接 `FieldMappingStoreTest` 的 4 例覆盖。
- §11 三行迁移 → B2/B3（留痕）、B4/B5（doc_chunk）、B6（field_mapping）；两张表在 A7 改写、B7 勾选。
- §12 提交纪律 → 每个 task 一个 commit，message 沿用 `阶段X(partY): 主题`。
- §6.9 schema 回写 → 本次无 schema 变更（验收口径第 6 条）。

**2. 占位符扫描**：全文无 `TBD` / `TODO`（仅在"留痕失败不反噬"这类设计说明里引用既有业务文案）/
"稍后补"；每个改代码的 step 都给了可粘贴的完整类或精确改写清单。
唯一的"二选一"是 B6 Step 10 的测试数量（56 或 57），已给判定规则且两种都满足 §12.1。

**3. 类型/签名一致性**（跨 task 交叉核对，已确认无命名漂移）：
- `DocumentService`：`replaceDocument(String,String,String,List<ChunkRow>)` / `countByDoc` / `deleteByDoc` / `listDocuments` / `loadDocContent` —— B4 定义、B5 四类调用方使用的名字一致。
- `DocChunkMapper`：`deleteByDoc` / `insertChunks` / `countByDoc` / `listDocuments` / `listChunkLines` / `searchByVector(String,int)` / `searchByKeyword(List<String>,int)` —— B4 接口、XML `id`、B5 `SearchServiceImpl` 调用三处对齐（`searchByVector` 首参已由 `float[]` 统一改为向量字面量 `String`）。
- `ExtractService`：`extractFromDoc` / `saveMappings` / `listMappings` / `listFieldMappings` / `confirm` —— B6 接口、`ExtractServiceImpl` 实现、`ExtractController` 与 `DraftServiceImpl` 调用、测试 stub 五处对齐。
- record 组件名：`ChunkRow(sectionPath,index,content,charCount,embedding:String)`、`VectorHit(...distance:double)`、`KeywordHit(...hits:int)`、`DocSummary(docId,docTitle,chunkCount)`、`DocContent(docTitle,text)`、`DocChunkLine(docTitle,sectionPath,content)`、`FieldMappingRow(8 组件)`、`FieldMappingRaw(6 组件)` —— 与其 `<resultMap>` 的 `<arg>` 顺序、`<foreach>` 的 `#{r.xxx}` 引用、Java 侧构造逐一对齐。
- 构造器参数个数：`DocumentServiceImpl(1)`、`SearchServiceImpl(3)`、`QaServiceImpl(3)`、`ExtractServiceImpl(4)`、`DraftServiceImpl(3)` —— 全部 ≤ 4（§2.8）。