# Agent 项目契约
> 本文件为AI Agent顶层入口，详细编码规范见外部文档。

## 基础信息
- 项目：Java SpringBoot DDD后端项目
- 构建：Maven
- 根包：com.lender.assistant

## 0. 开工前必读（硬性，不可跳过）

本项目全部编码规范集中在 `docs/agents/coding-standards.md`，它是唯一规范正文。

- **前置条件**：任何涉及新增或修改代码、调整配置、或回答"本项目该怎么写"的任务，第一步必须是 Read 该文件全文。读取完成前，不得输出代码、不得执行写文件操作、不得给出"符合规范"的结论。
- **自证**：读取后，首次回复的第一行输出 `编码规范已读：docs/agents/coding-standards.md`。
- **红线优先**：即使只改一行，也先读全文；§11 红线随时可能踩到。
- **禁止替代**：不得以"我记得规范""之前读过""只读了相关片段"为由跳过全文读取。
- **失败判定**：未读取即改动代码，本次任务视为失败，须撤回改动后重做。

## 🔗 外部引用文档（Agent必须读取）
- 编码规范：`docs/agents/coding-standards.md`
- 领域术语：`GLOSSARY.md`
- ADR：`docs/adr/`

## Agent skills

### Issue tracker

issue 以本地 markdown 文件形式存放在 `.scratch/<feature>/` 下。见 `docs/agents/issue-tracker.md`。

### Triage labels

五个规范角色，标签字符串与角色同名（`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`）。见 `docs/agents/triage-labels.md`。

### Domain docs

single-context：仓库根目录一个 `GLOSSARY.md` + `docs/adr/`。见 `docs/agents/domain.md`。
