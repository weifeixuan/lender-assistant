# ADR-0019：账户体系用自管会话与数据化角色

- 状态：已采纳（2026-11-13）
- 相关：`docs/prd/auth-and-login.md`、`docs/design/auth-and-login-spec.md`、`docs/schema.sql`、`pom.xml`、`AuthInterceptor`、`AuthServiceImpl`、`AuthBootstrapServiceImpl`

## 背景

系统此前没有任何身份体系：前端固定塞一个调试用户标识，后端靠可伪造的请求头取身份，文档管理与评测接口完全无鉴权。要支持「谁能看、谁能改、谁能管理账户」，必须引入真实账户、角色与登录态。

约束条件决定了取舍空间：

- 单实例部署，数据库是唯一的共享存储；
- 只服务页面登录相关接口，不对第三方开放 API；
- 已有 `{code,msg,data}` 统一响应、`GlobalExceptionHandler` 统一报错、`@RawResponse` 放行 SSE 与探针；
- 存量 `conversation.user_id` 已是 `TEXT`，系统内不存在第二个账户标识。

## 决策

### 1. 不引 `spring-boot-starter-security`，只用 `spring-security-crypto`

只使用 `BCryptPasswordEncoder`，认证入口是一个 `HandlerInterceptor`。

理由：全家桶引入即自动生成 `SecurityFilterChain`（默认全部请求需认证、开启 formLogin/httpBasic/CSRF），会改掉 `/health`、SSE、既有 curl 验收脚本的对外行为，必须逐一放行；且认证失败发生在 Filter 层，`@RestControllerAdvice` 捕获不到，需另写 `AuthenticationEntryPoint` 与 `AccessDeniedHandler` 才能维持 `{code,msg,data}`。拦截器抛 `BizException` 天然复用既有错误契约，只有一层断点。

### 2. 用服务端 opaque token，不用 JWT

令牌由 `SecureRandom` 生成 32 字节、Base64URL 编码，库中只存 SHA-256 摘要；有效期固定 7 天。

理由：本项目的权衡点全在「能不能撤销」——登出、禁用账户、令牌泄露止损都要求即时生效。JWT 要做到这点需要黑名单表或极短 TTL，等于把存储又加回来。JWT 的优势（无状态、跨服务）建立在多消费方假设上，而本项目单实例、无外部消费方。

### 3. 角色是数据，权限点是代码

角色、账户-角色、角色-权限都落库并可页面化管理；权限点固定 5 个写在枚举里，不建 `app_permission` 表。内置「系统管理员」在判定时短路为全部权限点。

理由：后端必须认识每个权限码才能在接口上校验，放进数据库会形成「库里有的码后端不认识」的第二套真相。内置角色短路保证新增权限点自动生效，不会因为忘记补关联表把管理员锁在门外。权限每次请求重算、不做进程内缓存，角色调整下一次请求即生效。

### 4. `user_id` 用 UUID v4 直接做主键

`app_user.user_id` 为 `TEXT` 主键，应用生成 UUID v4；`conversation.user_id` 类型不变，只改注释指向账户表。

理由：避免改类型时「管理员尚不存在」的时序死结，迁移退化为一条 `UPDATE`；全链路只有一个账户标识，不存在「该用 id 还是 user_id」的二义。代价是索引键从 8 字节变 36 字节，当前数据量可忽略。

## 后果

- 正面：错误契约、SSE 行为、现有测试基础设施都不需要为认证让步；登出与禁用即时生效；新增权限点不需要改内置角色数据。
- 负面：令牌存 localStorage 存在 XSS 暴露面（本期接受，前端不引入第三方脚本）；单实例会话无法直接横向扩展（本期不涉及）；BCrypt 有 72 字节口令上限，需在口令规则里显式校验而不是截断放行。
- 运维：初始管理员由环境变量在启动期幂等创建，缺失时只告警不阻断启动；历史调试会话归属迁移由启动引导触发，条件满足才改写。

## 被否决的替代方案

- **Spring Security 全家桶**：改造面覆盖 filter chain、放行清单、两个异常处理器与既有测试，换来的方法级授权、CSRF 等能力本期全部用不上。
- **JWT（access + refresh）**：多一套生命周期与密钥轮换，登出/禁用仍需回退到存储，等于用更复杂的方案实现同样的可撤销性。
- **`app_user.id` 自增 + `user_id` 业务键**：`conversation.user_id` 已是 `TEXT`，双标识会让认证上下文与数据归属各说各话，迁移与排障成本更高。
