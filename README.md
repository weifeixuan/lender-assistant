# 资方接入智能助手（lender-assistant）

面向**融资租赁资方接入**场景的 RAG + LLM 工程化实践项目：把多家资方的接口文档、日末文件说明做成可检索知识库，回答带引用溯源；按字段映射抽取新资方接入信息；再由映射表生成 Java 适配层代码草稿。

> **红线声明**：项目中所有资方（华信银行、瑞金租赁等）均为**虚构脱敏**素材，文档由脚本生成，不含任何真实机构信息；开发时间线真实。

## 核心能力

| 能力               | 说明                                          | 验收数字                             |
| ---------------- | ------------------------------------------- | -------------------------------- |
| 混合检索问答           | 向量 + 关键词双通道，RRF 融合排序，SSE 流式回答带【来源N】溯源       | 20条评估集 recall@4=100%（Mock向量，构造性） |
| 字段映射抽取           | 新资方文档 → 12 个内部标准字段的映射表 → 界面人工确认/修正          | 三份样例文档精确率/召回率 100%（Mock规则，构造性）   |
| Function Calling | 模型可调用"查已入库文件 / 查文档chunk数"两个真实业务查询           | SSE 真库验证两意图均正确触发                 |
| 韧性与留痕            | 熔断 + 重试（编程式 Resilience4j）+ 每次模型调用留痕；熔断走人工降级 | chaos 演练：3次失败→OPEN→快速失败，系统不崩     |
| 代码草稿生成           | 字段映射表 + 策略模式骨架 → Java 适配层草稿 + 人工复核声明        | 映射9条→草稿零占位残留                     |

**诚实声明**：Mock 实现产生的是构造性结果，只证明链路正确；真实模型效果待接入 key 后复测（provider 开关一键切换）。

## 架构

```
                ┌──────────── 韧性装饰（ResilientLlmExecutor：熔断→重试→留痕）────────────┐
                │                                                                      │
docx/txt 上传 ─▶ 解析(POI) ─▶ 切分(按章节/固定) ─▶ 向量化(Hash/智谱) ─▶ doc_chunk (pgvector, HNSW+trigram)
                                                                      │
提问 ─▶ SearchService（向量⇄关键词 双通道）─▶ RRF 融合排序 ─▶ QaService 拼 Prompt ─▶ AnswerGeneratorStrategy(Mock/OpenAI兼容) ─▶ SSE
                                            │
新资方文档 ─▶ ExtractService ─▶ ExtractGeneratorStrategy(Mock规则/OpenAI兼容) ─▶ field_mapping（人工确认状态机）
                                            │
映射表 ─▶ DraftService ─▶ DraftGeneratorStrategy(模板渲染/OpenAI兼容) ─▶ Java 适配层草稿 + 人工复核声明
                                            │
                            LenderQueryTools（@Tool：查入库文件/chunk数，Function Calling）
```

关键模块：`rag/`（检索问答）、`extract/`（抽取）、`draft/`（草稿）、`tools/`（Function Calling）、`resilience/`（熔断/重试/留痕）。

## 快速开始

### 方式一：Docker Compose 一键起（推荐）

```bash
git clone https://github.com/weifeixuan/lender-assistant.git
cd lender-assistant

# 国内网络先设镜像前缀（可选）
export REGISTRY_PREFIX=docker.1ms.run/    # Windows PowerShell: $env:REGISTRY_PREFIX="docker.1ms.run/"

docker compose up -d --build
# 等待构建完成后打开 http://localhost:8080
```

compose 会自动：建库建表（schema SQL 挂载到初始化目录）→ 数据库健康检查 → 启动应用（等它 ready 再对外）。

### 方式二：手动起

前置：JDK 21、Maven 3.8+、Docker（跑数据库）。

```bash
# 1. 起数据库（pgvector）
docker run -d --name lender-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=lender_assistant \
  -p 5432:5432 pgvector/pgvector:pg16

# 2. 建表
docker exec -i lender-pg psql -U postgres -d lender_assistant < docs/schema.sql

# 3. 打包并启动
mvn package -DskipTests
java -jar target/lender-assistant-0.1.0-SNAPSHOT.jar --server.port=8080
```

打开 <http://localhost:8080> 操作台，六个区块依次体验：① 上传文档 → ② 查看入库 → ③ 检索 → ④ 问答（SSE）→ ⑤ 字段抽取与人工确认 → ⑥ 代码草稿生成。

### 接入真实模型（可选）

| 开关                       | 值                | 说明   |
| ------------------------ | ---------------- | ---- |
| `RAG_CHAT_PROVIDER`      | `mock` / `openai` | 对话模型 |
| `RAG_EMBEDDING_PROVIDER` | `hash` / `zhipu`  | 向量化  |
| `RAG_EXTRACT_PROVIDER`   | `mock` / `openai` | 字段抽取 |
| `RAG_DRAFT_PROVIDER`     | `mock` / `openai` | 代码草稿 |

配 `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL` 三个环境变量即可接入真实模型，key 不进代码库（compose 里走环境变量透传）。
真实模型走 OpenAI 兼容协议，切换 DeepSeek / Kimi / 通义 / GLM 等只改这三个变量，代码不动：

```bash
# DeepSeek 官方
LLM_BASE_URL=https://api.deepseek.com  LLM_MODEL=deepseek-chat
# 其他兼容厂商：base-url 填到 /v1 这一级，模型名按厂商文档
```

向量化仍单独用 `ZHIPU_API_KEY`（智谱 embedding-3）。

## 设计决策（为什么）

| 决策                              | 理由                                                 | 详见                  |
| ------------------------------- | -------------------------------------------------- | ------------------- |
| RRF 融合替代"双通道优先"硬排序              | 硬排序让单一通道的宽泛命中挤掉双通道命中；RRF 按排名倒数求和，recall 94.7%→100% | docs/评估报告与阶段3记录     |
| 全链路 Mock 先行（端口双实现）              | key 没到位也能开发、调试、验收链路本身；真实效果单独复测不混数据                 | docs/plan_phase4.md |
| 编程式熔断（decorateSupplier）而非注解     | 单测不起容器即可驱动熔断状态机红绿循环                                | docs/阶段4_完成记录.md    |
| CB 在外 Retry 在内                  | 重试的每次尝试计入熔断统计，避免重试掩盖故障导致熔断永不打开                     | 同上                  |
| SSE 禁重试                         | token 已推给用户，重放即重复播报事故                              | 同上                  |
| 抽取/草稿"宁缺勿编"                     | 缺失字段渲染 TODO 而非编造值；错误参数的调用比不调用更糟                    | docs/plan_phase5.md |
| pgvector + JdbcTemplate（不用 JPA） | 需要大量 PG 扩展语法（HNSW/trigram/`<=>`），JPA 抽象反而挡路        | docs/阶段1 记录         |

## 技术栈

Spring Boot 3.5 · Spring AI 1.1（ChatClient / @Tool）· PostgreSQL 16 + pgvector 0.8（HNSW + trigram）· Resilience4j · Apache POI · SSE

## 项目文档

- `docs/plan_phase4.md` / `docs/plan_phase5.md`：实施计划
- `docs/阶段1~5_*.md`：各阶段完成记录（含验收数字、踩坑、设计决策）
- `docs/eval/`：评估集与基线报告
- `docs/gen_sample_docs.py`：虚构脱敏样例文档生成脚本
