# 资方接入智能助手（lender-assistant）

面向融资租赁资金方对接场景的智能助手，解决三个真实痛点：

1. **文档太多查不过来**：多家资方的接口文档、日末文件格式说明、差错处理规则 → RAG 检索问答，回答带引用溯源
2. **新资方字段映射靠人肉** → 大模型按 JSON Schema 结构化抽取，生成字段映射表
3. **适配层代码重复写** → 基于映射表自动生成 Java 适配层代码草稿，人工复核后使用

> 所有资方均为虚构脱敏样例（如"华信银行""瑞金租赁"），不含任何真实公司信息。

## 技术栈

Spring Boot 3.5 / Spring AI 1.1 / PostgreSQL(pgvector) / Docker Compose

## 快速开始（阶段 0 版本）

```bash
# 1. 配置模型 key（DeepSeek）
set DEEPSEEK_API_KEY=sk-xxx        # Windows CMD
# export DEEPSEEK_API_KEY=sk-xxx   # Git Bash / Linux

# 2. 启动
mvn spring-boot:run

# 3. 验证
curl http://localhost:8080/health
curl -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d "{\"message\":\"你好\"}"
```

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
