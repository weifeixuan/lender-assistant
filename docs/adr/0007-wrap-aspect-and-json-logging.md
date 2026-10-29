# ADR-0007：统一响应包装改为独立切面，日志切面外层定序，入参/出参 JSON 化

- 状态：已废弃（2026-10-28，回退 ADR-0001 的 `ApiResponseBodyAdvice`）
- 相关：`common/web/ApiResponseBodyAdvice`、`common/web/ControllerLogAspect`、`docs/adr/0001-unified-response-envelope.md`、`docs/adr/0003-tech-stack-and-exclusions.md`

## 背景

原机制（ADR-0001）用 `ApiResponseBodyAdvice`（`ResponseBodyAdvice`）在响应出站时包装 `{code,msg,data}`，日志切面 `proceed()` 只能拿到包装前的原始值——出参日志与真实响应存在"两层皮"。需求要求出参日志直接包含 code/msg/data，即"日志记录的对象就是真正写出的响应体"。

原 ADR-0001 的核心理由"`@Around` 无法改变 controller 方法声明的返回类型"经不起推敲：MVC 选消息转换器看的是返回值的**运行时类型**，切面替换返回对象后序列化完全正常。真正的机制代价只是需要逐类排除特殊返回类型，这一点 `ResponseBodyAdvice` 同样要做。

## 决策

1. 新建 **`ResponseWrapAspect`（`@Order(2)`，内层）**：`@Around` 包装普通返回值为 `ApiResponse`；排除 `ApiResponse`（幂等）/ `SseEmitter` / `ResponseEntity` / `@RawResponse`（方法级与类级）。
2. **`ControllerLogAspect`（`@Order(1)`，外层）**：职责不变（入参/出参/耗时/异常日志），仅声明 `@Order`。进入时记入参，`proceed()` 返回的已是内层包装后的 `ApiResponse`，出参日志天然含 code/msg/data。
3. **删除 `ApiResponseBodyAdvice`**，排除规则随切面迁移；`GlobalExceptionHandler` **完全不动**。
4. 日志入参/出参统一 **JSON 打印**：用容器 `ObjectMapper`（与响应序列化同一份配置）；`MultipartFile` 只记元信息、`SseEmitter` 只记占位；Map 脱敏发生在序列化之前；序列化失败降级 `String.valueOf`；超长 JSON 整体省略只留原始长度（半截 JSON 对采集端无意义）。

## 理由

1. 日志与响应同源：外层切面 `proceed()` 拿到的对象就是内层即将写出的对象，杜绝两层皮。
2. 职责单一：包装与日志分属两个切面，日志切面除 `@Order` 外零逻辑改动。
3. 异常处理**必须**留在 `@ExceptionHandler`：404（无 handler 可代理）与参数绑定/报文解析异常（发生在方法调用之前）都在切面视野之外，切面 catch 会漏——修订 ADR-0003 对"响应体改写"的 AOP 禁令，但**保留**对"异常处理"的禁令。
4. JSON 用 Jackson 而非 fastjson：不新增依赖，且与响应序列化同源；日志即真实响应的前提是两处配置一致。

## 废弃原因（2026-10-28 实测）

`@Around` 返回 `ApiResponse` 时，controller 被 CGLIB 代理后，生成的代理方法会把返回值强制转换回声明类型。
所有返回 `List` 的接口（`GET /api/admin/documents`、`POST /api/admin/documents/search`）运行时抛：

```
java.lang.ClassCastException: ApiResponse cannot be cast to java.util.List
```

该问题在单元测试中不可见——测试直接调用切面方法、绕过了 CGLIB 代理层；真实启动后才暴露。
ADR 中"切面替换返回对象后序列化完全正常"的判断只对消息转换器成立，忽略了代理方法的返回值强转。
已回退为 `ApiResponseBodyAdvice`：它在出站阶段替换 body，不改 JVM 方法返回值，机制上不可能触发该异常。
代价是出参日志回到"原始返回值"，不再与响应体逐字同源（可接受）。

## 后果

- **顺序是隐式契约**：Spring 对无 `@Order` 的切面不保证相对顺序，配错不会报错、只会让出参日志悄悄变回原始值——由 `ResponseWrapAspectTest#orderContractLocked` 用例钉死（1 < 2）。
- String 返回类型从"不包装"变为"正确包装"（转换器按运行时类型选择）；当前代码库无 String 返回接口，无实际影响。
- 长期约束：controller 方法不得 final / static / 同类自调用（Spring AOP 代理边界），否则包装缺失；非 `@RestController` 的 handler 不在切点内。
- 异常路径日志不变：切面 catch 只记 ERROR + 堆栈后重抛，失败响应的 code/msg 由 `GlobalExceptionHandler` 的 WARN 日志承载。
- 日志格式变化：入参/出参从 toString 变为 JSON（含脱敏后的结构），依赖日志文本格式的既有脚本/验收需注意。
