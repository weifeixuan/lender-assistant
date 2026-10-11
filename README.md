# 资方接入智能助手（lender-assistant）

面向融资租赁资金方对接场景的智能助手，解决三个真实痛点：

1. **文档太多查不过来**：多家资方的接口文档、日末文件格式说明、差错处理规则 → RAG 检索问答，回答带引用溯源
2. **新资方字段映射靠人肉** → 大模型按 JSON Schema 结构化抽取，生成字段映射表
3. **适配层代码重复写** → 基于映射表自动生成 Java 适配层代码草稿，人工复核后使用

> 所有资方均为虚构脱敏样例（如"华信银行""瑞金租赁"），不含任何真实公司信息。

## 技术栈

Spring Boot 3.5 / Spring AI 1.1 / PostgreSQL(pgvector) / Docker Compose

## 快速开始（阶段 1 版本）

```bash
# 1. 起数据库（pgvector 容器）
docker run -d --name lender-pg -e POSTGRES_PASSWORD=<dev密码> -e POSTGRES_DB=lender_assistant \
  -p 5432:5432 -v lender_pgdata:/var/lib/postgresql/data pgvector/pgvector:pg16

# 2. 建表
docker exec -i lender-pg psql -U postgres -d lender_assistant < docs/schema_phase1.sql

# 3. 启动应用（Embedding 默认用本地 Mock，无需任何 API key）
mvn spring-boot:run

# 4. 上传文档（解析→切分→向量化→入库，返回各环节统计）
curl -X POST http://localhost:8080/api/admin/documents \
  -F "file=@docs/sample_docs/华信银行_日末文件格式说明_v1.0.docx" -F "docId=huaxin_eod_format_v1"

# 5. 混合检索
curl -X POST http://localhost:8080/api/admin/documents/search \
  -H "Content-Type: application/json" -d '{"query":"逾期文件第3列是什么","topK":3}'
```

配置开关（application.yml）：
- `rag.embedding.provider`: `hash`（本地 Mock，默认）/ `zhipu`（需 ZHIPU_API_KEY 环境变量）
- `rag.split.strategy`: `section`（按标题段落，默认）/ `fixed`（固定字数滑动窗口）

## 模块分层

```
com.lender.assistant
├── controller   # REST 接口
├── service      # 业务编排（对话、留痕）
├── rag          # 文档解析、切分、向量入库、混合检索（阶段 1-3）
├── extraction   # JSON Schema 字段映射抽取（阶段 4）
├── codegen      # 适配层代码草稿生成（阶段 5）
└── infra        # 配置、韧性（Resilience4j）、调用留痕
```
