# Domain Docs

各工程类 skill 在探索本仓库代码时，应如何消费本仓库的领域文档。

## 探索之前，先读这些

- 仓库根目录的 **`GLOSSARY.md`**，或
- 仓库根目录的 **`GLOSSARY-MAP.md`**（若存在）：它指向每个 context 一个 `GLOSSARY.md`。读取与当前主题相关的每一个。
- **`docs/adr/`**：读与你即将改动的区域相关的 ADR。多 context 仓库里还要查 `src/<context>/docs/adr/` 下的 context 级决策。

若上述文件不存在，**静默继续**。不要提示缺失、不要建议提前创建。`/domain-modeling` skill（经 `/grill-with-docs` 与 `/improve-codebase-architecture` 到达）会在术语或决策真正被确定时懒创建它们。

## 文件结构

单 context 仓库（大多数仓库）：

```
/
├── GLOSSARY.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

多 context 仓库（根目录存在 `GLOSSARY-MAP.md`）：

```
/
├── GLOSSARY-MAP.md
├── docs/adr/                          ← 系统级决策
└── src/
    ├── ordering/
    │   ├── GLOSSARY.md
    │   └── docs/adr/                  ← context 级决策
    └── billing/
        ├── GLOSSARY.md
        └── docs/adr/
```

## 使用 glossary 的词汇

当你的输出要命名一个领域概念（在 issue 标题、重构提案、假设、测试名里），使用 `GLOSSARY.md` 定义的那个术语。不要漂移到 glossary 明确回避的同义词。

若你需要的概念还不在 glossary 里，这是一个信号：要么你在发明项目并不使用的语言（重新考虑），要么存在真实的缺口（记下来交给 `/domain-modeling`）。

## 标记 ADR 冲突

若你的输出与既有 ADR 相抵触，要显式提出来，而不是悄悄覆盖：

> _与 ADR-0007（event-sourced orders）冲突，但值得重开，因为……_

## 代码规范
- `docs/agents/coding-standards.md`: Java项目编码、DDD包、设计模式实现规范
