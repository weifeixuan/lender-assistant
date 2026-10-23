# ADR-0001：统一响应包装用 ResponseBodyAdvice，不用 AOP 切面返回值

- 状态：包装机制部分已被 [ADR-0007](0007-wrap-aspect-and-json-logging.md) 修订（ResponseBodyAdvice → 切面包装）；响应形状 `{code,msg,data}` 与异常走 `@ExceptionHandler` 的决策仍然有效（2026-10-23）
- 相关：`common/web/ApiResponseBodyAdvice`、`common/web/ControllerLogAspect`、`common/dto/ApiResponse`、`common/annotation/RawResponse`、`src/main/resources/static/index.html`

## 背景

需求要求"把全局 controller 的返回实体包装为 `code / msg / data`"。同一批需求还要求"用 Spring AOP 打印 controller 的输入输出日志"。
两件事容易被混为一谈，都往"用 AOP 实现"上靠。

## 决策

- **统一响应包装**用 Spring MVC 的 `ResponseBodyAdvice`（配 `@RestControllerAdvice` 注册），在响应体出站时改写。
- **AOP 切面只做日志**（`ControllerLogAspect`，`@Around` 打印进出参 / 耗时），**不改动响应体**。

## 理由

1. `@Around` 无法改变 controller 方法声明的返回类型：想在切面里换成统一响应体，只能靠同样"出站改写"的机制，等于用切面绕远路。
2. `ResponseBodyAdvice` 是官方为"响应体出站改写"设计的扩展点，一处装配全局生效；控制器代码保持原样，不需要逐个手写包装。
3. 异常 → 响应也**不放在切面里 catch**，而是走 `@ExceptionHandler`（见 `GlobalExceptionHandler`）：官方异常解析链能正确处理内容协商与 HTTP 状态，切面里自行 catch 再拼响应体会与 MVC 职责重叠、易漏类型。

## 后果

- 需排除三类不该包装的响应：`SseEmitter`（SSE 会破坏事件协议）、`String`（`StringHttpMessageConverter` 类型不匹配）、`@RawResponse`（`/health` 探针需保持原结构）。排除规则集中在 `ApiResponseBodyAdvice.supports()`，并有单测锁死。
- 已包装的 `ApiResponse` 必须原样返回（幂等），否则异常处理器产出的响应会被二次包装成 `code` 套 `code`。
- **破坏性影响**：所有普通接口响应体形状变为 `{code,msg,data}`，静态操作台 `index.html` 已同步改造为读取 `body.data` 与 `body.msg`。
