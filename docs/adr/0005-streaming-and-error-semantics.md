# ADR-0005：SSE 流式约定与 MVC 错误语义归位

- 状态：已采纳
- 相关：`docs/agents/coding-standards.md §5.9 / §8.2`、`rag/service/impl/QaServiceImpl`、`common/web/GlobalExceptionHandler`、`common/dto/ApiResponse`

## 背景

两件在真机冒烟中暴露的问题：

1. **假流式**：`SseEmitter` 若在请求线程内完成检索 / 生成 / `send`，`ResponseBodyEmitter` 会把事件缓冲到控制器方法返回才 flush，导致 TTFB≈总耗时，并在客户端预览层触发 `net::ERR_ABORTED`。
2. **错误被兜底成 500**：Spring MVC 自身的客户端错误异常（路由不存在、报文不可解析、参数类型不匹配、必填参数缺失）被全局处理器兜底成 500，语义与文案都错。这类问题单测覆盖不到 MVC 异常解析链。

## 决策

- **SSE**：方法**立即 `return emitter`**，检索 / 生成 / `send` 全部放到独立线程（`streamExecutor`）执行。
- **MVC 错误归位**：`NoResourceFoundException` / `NoHandlerFoundException` → 404；`HttpMessageNotReadableException` / `MethodArgumentTypeMismatchException` / `MissingServletRequestParameterException` → 400。文案取 `ErrorCode` 默认值，**不回显框架的技术性异常信息**。

## 理由

1. `ResponseBodyEmitter` 的 flush 时机绑定在控制器返回之后；把工作留在请求线程内等于主动放弃流式收益。
2. 客户端错误属于「调用方用错了接口」，用 5xx 表达会误导调用方与监控告警；按 HTTP 语义归位才能让上游正确决策重试与否。
3. 不暴露框架异常原文，避免把内部类名、解析细节泄漏为对外契约（与 ADR-0001「不暴露堆栈 / 内部类型」一致）。

## 后果

- SSE 相关的检索 / 生成逻辑必须是无共享可变状态的独立任务；异常在独立线程内须自行捕获并落日志，不能指望请求线程的异常处理器。
- `GlobalExceptionHandler` 需要逐类显式声明 MVC 客户端异常，缺失声明会退化为 500；此类分支只能靠真实启动冒烟验证（单测盲区），故列入 coding-standards「遗留待办」的冒烟测试范围。
