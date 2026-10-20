# Agent 项目契约
> 本文件为AI Agent顶层入口，详细编码规范见外部文档。

## 基础信息
- 项目：Java SpringBoot DDD后端项目
- 构建：Maven
- 根包：com.lender.assistant

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
