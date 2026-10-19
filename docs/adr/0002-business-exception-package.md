# ADR-0002：业务异常与错误码落 `common/exception`（修订 AGENTS.md §0.2）

- 状态：已采纳（2026-10-19）
- 相关：`common/exception/BizException`、`common/exception/ErrorCode`、`common/web/GlobalExceptionHandler`、`AGENTS.md §0.2 / §5.8`

## 背景

`AGENTS.md §0.2` 原规划把"全局异常处理 + 业务异常"放在**顶层 `exception/`** 包。
本次需求要求业务异常定义在 `common.exception` 下，两者冲突。

## 决策

- 业务异常与错误码落在 `common/exception/`（`BizException` + `ErrorCode`）。
- 顶层 `exception/` 不再使用，**同步修订 `AGENTS.md §0.2` 结构图**（该包从未真正落地，无迁移成本）。

## 理由

1. 异常是本项目**所有领域共用**的跨领域内容，与 `common/util`、`common/dto` 同类，归 `common/` 与 §0.3"两级都定不了再考虑 common"的定位一致。
2. 顶层技术包（`controller/` / `service/` / `mapper/`）已被明令禁止；顶层再留一个 `exception/` 技术包，与该方向相悖。
3. 放 `common/exception` 后，`controller`（抛 `BizException`）、`service`（抛 / 包装）、`common/web`（`GlobalExceptionHandler` 消费）三方依赖方向自然，无反向依赖。

## 后果

- `AGENTS.md §0.2` 的 `common/` 块扩充为 `annotation / constant / dto / exception / util / web`，顶层 `exception/` 行删除。
- §5.8 明确对外响应契约：普通接口 `ApiResponse{code,msg,data}`，失败只给通用 / 可读文案，SSE 与探针用 `@RawResponse` 排除。
- 业务代码此后禁止用裸 `RuntimeException` 做流程控制（§5.3）——用 `BizException(ErrorCode)` 承载语义与错误码。
