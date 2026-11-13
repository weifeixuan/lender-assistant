# 登录与账户体系改造 · 需求文档

> 状态：待评审（评审通过后再进入实现）
> 范围：后端 `lender-assistant` + 前端 `lender-assistant-frontend`
> 关系：本文**推翻** `docs/prd/multi-turn-backend.md` §1.2「多用户鉴权与真实登录体系」为范围外的结论，以及前端 `AGENTS.md` §7「页面不提供登录、用户菜单、设置」的红线；两处需按第 12 节同步改写。
> 命名约定：文档文件名使用易懂的英文单词，不使用抽象缩写（本文即 `auth-and-login.md`）。

---

## 1. 目标与非目标

### 1.1 目标

1. 提供**真实账户体系**：账户表、口令校验、服务端会话、登出、禁用账户。
2. 侧边导航改为**一级 / 二级结构**：
   - 一级「知识库」下挂原有两个页面：文档管理、知识库对话；
   - 新增一级「系统设置」，下挂二级「账户管理」「角色管理」。
3. 新增登录页、账户管理页、角色管理页。
4. 权限模型按「**角色是数据**」设计：角色的增删改、角色与权限点的关系、账户与角色的多对多分配，全部可在页面上完成。
5. `user_id` 重新设计为**单一标识**（`TEXT` + UUID 主键），全链路（会话归属、认证上下文、前端身份传递）统一，不保留新旧两套兼容层。

### 1.2 非目标（本次不做）

- 找回密码、短信/邮箱验证、二次验证、OAuth/SSO、LDAP 对接。
- 对外部服务开放 API Key / OAuth2 鉴权。将来外部调用另设方案，本文不预留通用能力。
- 细粒度到字段/数据行的数据权限（如「只能看自己上传的文档」）。
- 角色继承、角色层级、数据范围。
- 账户删除、用户名修改、头像、个人资料编辑。
- 多实例部署下的分布式会话与共享缓存（本项目当前单实例 + PostgreSQL）。
- 前端暗色主题、i18n。

---

## 2. 需求边界

### 2.1 页面与导航结构

| 一级 | 二级 | 路由 | 所需权限点 | 来源 |
|---|---|---|---|---|
| 知识库 | 文档管理 | `/knowledge/documents` | `knowledge.documents.view`（写操作另需 `knowledge.documents.write`） | 原有页面，仅迁移路径与层级 |
| 知识库 | 知识库对话 | `/knowledge/chat` | `knowledge.chat.use` | 原有页面，仅迁移路径与层级 |
| 系统设置 | 账户管理 | `/settings/accounts` | `settings.accounts.manage` | 新增 |
| 系统设置 | 角色管理 | `/settings/roles` | `settings.roles.manage` | 新增 |
| — | 登录 | `/login` | 匿名 | 新增，不进侧边栏、不套 `AppShell` |

导航交互规则：

- `/` 重定向到 `/knowledge/documents`；`/documents`、`/chat` 保留前端 `Navigate replace` 重定向到新路径，仅做旧链接兼容，不做后端兼容。
- 一级为**可展开组**，进入子页时自动展开并高亮；「系统设置」即使只有两个子项也保留组结构，默认展开。
- 窄屏（56px 折叠态）下点击一级图标直接跳转到该组**第一个当前账户可见的子页**，不做悬浮浮层。
- 面包屑改为「一级 / 二级」，不再写死「知识库 / 当前页」。
- 一级是否可见 = 该组下至少有一个当前账户可见的子页；账户无任何子页权限时该一级整体隐藏。

### 2.2 账户与角色边界

| 能力 | 账户管理页 | 角色管理页 |
|---|---|---|
| 列表 | 用户名、显示名、角色、状态、创建时间 | 角色名、描述、权限点数、引用账户数、是否内置 |
| 新增 | 用户名、显示名、初始密码、角色（可多选） | 角色名、描述、权限点（多选） |
| 修改 | 显示名、角色（**必要项**，见下） | 名称、描述、权限点（内置角色除外） |
| 口令 | 重置他人密码、修改自己密码 | — |
| 状态 | 启用 / 禁用 | — |
| 删除 | 不做 | 自定义角色可删；内置角色不可删 |
| 不做 | 删除账户、改用户名 | 角色继承、角色排序、角色复制 |

> **对第一轮 Q10 的一处修正**：原结论「不做角色二次编辑」在多角色模型下不成立——若账户创建后不能再调整角色，角色管理页勾选的权限永远无法落到账户身上。因此账户管理页**必须**支持调整账户的角色集合，仍不提供删除账户与改用户名。

硬约束（后端校验，前端同步提示）：

1. 不能禁用自己。
2. 不能对**最后一个具备账户管理能力的账户**做禁用，也不能以任何方式移除其管理能力（清空角色、改角色集合导致不再含 `settings.accounts.manage`、移除其内置管理员角色）。判定口径按**操作完成后的系统状态**计算：操作后必须仍存在至少一个启用状态、且具备账户管理能力的账户。具备账户管理能力 = 拥有内置管理员角色，或其任一角色含 `settings.accounts.manage`。
3. 内置角色「系统管理员」不可删除、不可改名、不可改权限，代码中短路为拥有全部权限点（新增权限点自动生效，杜绝把自己锁在门外）。
4. 自定义角色若仍有账户引用，拒绝删除并返回引用账户数。
5. 用户名唯一、角色名唯一，均不区分大小写比较。

### 2.3 权限点目录（v1 固定 5 个）

| 权限点 | 中文名 | 控制对象 |
|---|---|---|
| `knowledge.documents.view` | 查看文档 | 文档管理页、文档列表、切片、检索测试 |
| `knowledge.documents.write` | 维护文档 | 上传、覆盖重建、删除文档 |
| `knowledge.chat.use` | 使用知识库对话 | 对话页、`/api/qa`、会话列表/历史/删除 |
| `settings.accounts.manage` | 管理账户 | 账户管理页及其全部接口 |
| `settings.roles.manage` | 管理角色 | 角色管理页及其全部接口 |

- 权限点目录**写在代码里**（`code` / `desc` 双字段枚举），不建 `app_permission` 表。理由：后端必须知道每个码才能在接口上做校验，放进数据库会形成「库里有的码后端不认识」的第二套真相。
- `role_permission` 只存角色与权限码的关联，落库值取枚举 `code()`；解析时未识别的码直接拒绝，不静默忽略。
- 菜单可见性由权限点推导，不单独维护「菜单可见」字段。

---

## 3. 已定决策与理由

### 3.1 决策清单

| 编号 | 决策 | 结论 |
|---|---|---|
| D1 | 登录的真实程度 | 真实账户体系（账户表 + 口令 + 服务端会话） |
| D2 | 初始管理员产生方式 | 应用启动时按环境变量幂等创建，缺失则跳过并打 WARN |
| D3 | 认证技术栈 | `spring-security-crypto`（仅 BCrypt）+ 自写拦截器，不引 `spring-boot-starter-security` |
| D4 | 令牌形态 | 服务端 opaque token（会话表），不用 JWT |
| D5 | 令牌传输 | `Authorization: Bearer <token>`，前端存 localStorage |
| D6 | 鉴权范围 | 全部 `/api/**` 需登录，仅 `/api/auth/login` 与 `/health` 匿名 |
| D7 | 权限模型 | 角色是数据；账户多角色；权限取并集；内置管理员角色短路全权限 |
| D8 | `user_id` | `TEXT`，应用生成 UUID v4，直接做主键，全链路唯一标识 |
| D9 | 会话生命周期 | 固定 7 天、允许多设备、登出仅失效当前令牌、登录时清理该账户过期会话 |
| D10 | 存量 `dev-user-001` | 启动期一次性迁移到初始管理员，不做两套 id 并存，前端 `DEV_USER_ID` 删除 |
| D11 | 路由路径 | 层级化路径 + 旧路径前端重定向 |
| D12 | 未登录行为 | 受保护路由跳 `/login?redirect=`，401 清 token 后跳登录，403 提示权限不足 |

### 3.2 认证技术选型对比（决定性依据）

先说前提：本套认证**只服务页面登录相关接口**；将来对接外部服务另设鉴权方式或不鉴权，因此不需要为外部调用预留通用能力。`pom.xml` 当前没有任何 Spring Security 依赖，现有接口依赖 `ApiResponseBodyAdvice` 统一包装、`GlobalExceptionHandler` 统一报错、`@RawResponse` 放行 SSE 与探针。

#### A. Spring Security 全家桶 vs `spring-security-crypto` + 自写拦截器

| 维度 | 全家桶 | crypto + 自写 |
|---|---|---|
| 自动配置侵入 | 引入即生成 `SecurityFilterChain`：默认所有请求需认证、开启 formLogin/httpBasic、默认开 CSRF。`/health`、SSE、curl 验收脚本、无鉴权单测立刻受影响，必须逐一放行 | 零自动配置，只用到 `BCryptPasswordEncoder` 一个类 |
| 错误契约 | 认证失败发生在 Filter 层，早于 `DispatcherServlet`，`@RestControllerAdvice` 捕获不到；返回空 401/403 或 HTML，破坏 `{code,msg,data}`，必须自写 `AuthenticationEntryPoint` + `AccessDeniedHandler` | 拦截器抛 `BizException`，天然走既有 `GlobalExceptionHandler`，与现有 400/404/409 语义一致 |
| 配置量 | 至少一个 `SecurityConfig`（filter chain、放行清单、CSRF 关闭、session 策略、两个异常处理器），外加 `spring-security-test` 与既有测试改造 | 一个拦截器 + `WebMvcConfig` 加一行路径注册，与现有 `UserIdInterceptor` 同形态 |
| 调试成本 | 两层过滤器（Security + 业务拦截器）叠加，401/403/BizException 三种来源难区分；SSE 被包装后的行为要单独验 | 单层，断点位置唯一 |
| 本期用不上的收益 | 方法级 `@PreAuthorize`、CSRF、Remember-Me、OAuth2 客户端、完整安全默认值 | — |

结论：全家桶的收益本期全部用不上，而它的默认值恰好会改掉现有接口的对外行为——这是本次最贵的一笔「免费改造」。

#### B. JWT 无状态 vs 服务端 opaque token

| 判断项 | JWT | opaque（会话表） |
|---|---|---|
| 即时登出 | 做不到，需黑名单表或极短 TTL，等于又引入存储 | 删一行即失效 |
| 禁用账户生效 | 需每请求查库或等 TTL，前者等于失去无状态收益 | 天然即时 |
| 密钥轮换 | 需双密钥并存验证窗口，或全员重登 | 无密钥概念 |
| 令牌泄露止损 | 有效期内无法撤销（除非黑名单） | 可精确删除单个或该账户全部会话 |
| 刷新令牌 | 通常要 access + refresh 两套，前端要写续期逻辑 | 单令牌，固定有效期 |
| 多实例扩展 | 无状态占优 | 需共享存储（本项目单实例 + PostgreSQL，本就有） |
| 外部消费方 | 适合多服务互调 | 不适合对外开放 |

本项目的权衡点全在「**能不能撤销**」（踢人、禁用、止损），而 JWT 的全部优势（无状态、跨服务）都建立在「多消费方 + 难撤销」的假设上——本文已明确该假设不成立。

#### C. 业界常见做法

「Spring Boot 单体 + React SPA + 单实例 + 无外部消费方」的内部管理后台，主流仍是**服务端会话**（Spring Security 原生 session 或自管 session 表）。JWT 的高频场景是开放 API、多微服务、移动端/第三方客户端，恰好都是本项目明确不具备的条件。

#### D. 结论与被否决项的代价

采用：`spring-security-crypto`（仅 BCrypt）+ 自写认证拦截器 + opaque token 会话表。令牌用 `SecureRandom` 生成 32 字节，数据库**只存 SHA-256 摘要**，库被读走也拿不到可用令牌。

- 上全家桶的代价：约 1 个 `SecurityConfig` + 2 个异常处理器 + 放行清单 + `spring-security-test` + 既有测试改造；漏配 CSRF/formLogin 会直接打挂 `/health` 与 SSE，排障落在最贵的验收环节。
- 上 JWT 的代价：多一套 access/refresh 生命周期与密钥配置；禁用/登出要么延迟生效，要么加黑名单表退化成 opaque；排障面覆盖签名、时钟、黑名单三块。

### 3.3 `user_id` 设计

- `app_user.user_id` = `TEXT`，应用生成 UUID v4，**直接做主键**，不另设自增列。
- 理由：`conversation.user_id` 已是 `TEXT`，无需类型迁移；迁移退化为一条 `UPDATE`，不存在「改类型时管理员尚不存在」的时序死结；全链路只有一个账户标识，不会出现「该用 id 还是 user_id」的二义。
- 代价：索引键从 8 字节变 36 字节，当前数据量可忽略。
- `user_id` 不写入前端可猜测的位置（不出现在 URL 查询参数中，仅账户管理接口路径按需使用）；前端日常请求只带令牌，不传账户标识。

### 3.4 会话生命周期

- 有效期固定 7 天，不做滑动续期（避免每请求写库）。
- 允许多设备并存；登出只失效当前令牌。
- 每次请求校验：令牌存在、未过期、对应账户为启用状态。
- 不做定时清理任务；**登录成功时**顺手删除该账户已过期的会话行。

### 3.5 登录页与未登录行为

- 未登录访问受保护路由 → 跳 `/login?redirect=<原路径>`，登录成功回跳；`redirect` 只接受站内相对路径（防开放重定向）。
- 受保护接口返回 401 → `client.ts` 清除本地令牌并派发 `auth:unauthorized` 事件，`AuthProvider` 监听后跳登录。**`POST /api/auth/login` 自身的 401 例外**：只在登录页展示「用户名或密码错误」，不清令牌、不跳转。
- 接口返回 403 → 页面提示「无访问权限」，**不跳登录**（否则账户会反复输密码）。
- 登录失败统一文案「用户名或密码错误」，不区分账户不存在、口令错误与账户已禁用。
- 侧边栏底部显示当前登录用户名 + 退出入口。
- `VITE_USE_MOCK=true` 时采用本地假登录（内存中的 mock 管理员与全量权限），否则前端单独开发会被登录页完全挡住。

---

## 4. 术语（同步补入 `GLOSSARY.md`）

| 词 | 含义 |
|---|---|
| 账户（account） | 可登录的系统实体，含用户名、显示名、口令摘要、状态 |
| 角色（role） | 权限点的集合，可自定义增删改；「系统管理员」为内置角色 |
| 权限点（permission） | 代码中固定定义的授权单元（如 `knowledge.chat.use`），是授权的唯一最小单位 |
| 登录态 / 会话（session） | 一次登录产生的服务端记录，由不透明令牌标识，可单独失效 |
| 内置角色 | 「系统管理员」，不可删除/改名/改权限，隐式拥有全部权限点 |
| 初始管理员 | 首次启动时由环境变量创建的管理员账户，用于打破「无人能进账户管理」的冷启动死锁 |

> 用词约定：正文涉及该实体时一律写「账户」，不写「账号」或「用户」；`username` 字段仍称「用户名」，页面名「账户管理」、权限点「管理账户」、接口路径 `accounts` 保持不变。

---

## 5. 表结构（唯一事实源：`docs/schema.sql`）

所有新表必须带 `COMMENT ON TABLE` / `COMMENT ON COLUMN`；时间字段沿用 `create_time` / `update_time` 口径；状态值使用 `ENABLED` / `DISABLED`。

### 5.1 `app_user`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `user_id` | `TEXT` | PK | UUID v4，应用生成，全链路唯一账户标识 |
| `username` | `TEXT` | NOT NULL, UNIQUE | 登录名，`[A-Za-z0-9_-]{3,32}`，比较时不区分大小写 |
| `display_name` | `TEXT` | NOT NULL | 展示名，1–32 字符 |
| `password_hash` | `TEXT` | NOT NULL | BCrypt 摘要，永不返回前端 |
| `status` | `TEXT` | NOT NULL DEFAULT `'ENABLED'` | `ENABLED` / `DISABLED` |
| `create_time` | `TIMESTAMPTZ` | NOT NULL DEFAULT now() | |
| `update_time` | `TIMESTAMPTZ` | NOT NULL DEFAULT now() | |

索引：`CREATE UNIQUE INDEX uk_app_user_username_lower ON app_user (lower(username));`

### 5.2 `app_role`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `role_id` | `TEXT` | PK | UUID v4 |
| `name` | `TEXT` | NOT NULL | 角色名，1–32 字符；唯一性由下方 `lower()` 函数唯一索引保证 |
| `description` | `TEXT` | 可空 | 描述 |
| `builtin` | `BOOLEAN` | NOT NULL DEFAULT false | 内置角色标记；唯一内置角色为「系统管理员」 |
| `create_time` / `update_time` | `TIMESTAMPTZ` | NOT NULL DEFAULT now() | |

索引：`CREATE UNIQUE INDEX uk_app_role_name_lower ON app_role (lower(name));`（与 `app_user` 的 `lower(username)` 口径一致，保证角色名不区分大小写唯一）。

### 5.3 `user_role`

| 列 | 类型 | 约束 |
|---|---|---|
| `user_id` | `TEXT` | NOT NULL, FK → `app_user.user_id` ON DELETE CASCADE |
| `role_id` | `TEXT` | NOT NULL, FK → `app_role.role_id` ON DELETE RESTRICT |
| 主键 | | `(user_id, role_id)` |

索引：`CREATE INDEX idx_user_role_role ON user_role (role_id);`（角色删除前的引用计数用）。

### 5.4 `role_permission`

| 列 | 类型 | 约束 |
|---|---|---|
| `role_id` | `TEXT` | NOT NULL, FK → `app_role.role_id` ON DELETE CASCADE |
| `permission_code` | `TEXT` | NOT NULL |
| 主键 | | `(role_id, permission_code)` |

不建 `app_permission` 表，理由见 2.3。

### 5.5 `user_session`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `token_hash` | `TEXT` | PK | 令牌的 SHA-256 十六进制摘要，**不存原文** |
| `user_id` | `TEXT` | NOT NULL, FK → `app_user.user_id` ON DELETE CASCADE | |
| `expire_time` | `TIMESTAMPTZ` | NOT NULL | 创建时间 + 7 天 |
| `create_time` | `TIMESTAMPTZ` | NOT NULL DEFAULT now() | |

索引：`CREATE INDEX idx_user_session_user ON user_session (user_id);`（登出全端、登录清理用）。

### 5.6 `conversation` 的调整

- `conversation.user_id` **类型不变**（`TEXT`），值改为 `app_user.user_id`。
- **不新增外键**：存量行在迁移完成前不满足约束，且本项目不提供删除账户能力，弱引用风险可控；在 `COMMENT ON COLUMN` 中写明「值为 `app_user.user_id`」。

---

## 6. 接口契约

### 6.1 通用约定

- 除 `POST /api/auth/login` 与 `GET /health` 外，所有 `/api/**` 必须携带 `Authorization: Bearer <token>`。
- 普通接口统一 `{code, msg, data}`，时间格式 `YYYY-MM-DD HH:mm:ss`。
- 新增错误码：

| 枚举 | code | HTTP | 场景 |
|---|---|---|---|
| `UNAUTHORIZED` | 401 | 401 | 受保护接口：令牌缺失/格式非法/不存在/已过期/账户已禁用；登录接口：凭据错误（见 6.2） |
| `FORBIDDEN` | 403 | 403 | 已登录但缺少所需权限点 |
| `USERNAME_EXISTS` | 409 | 409 | 用户名已存在（不区分大小写） |
| `ROLE_NAME_EXISTS` | 409 | 409 | 角色名已存在 |
| `ROLE_IN_USE` | 409 | 409 | 角色仍被账户引用，拒绝删除 |
| `ROLE_PROTECTED` | 400 | 400 | 内置角色不允许改名/改权限/删除 |

- 口令规则（唯一口径）：长度最少 8 个字符（按 Unicode 码点计），最多 72 个**字节**（按 UTF-8 编码计）。BCrypt 只处理前 72 字节，超长会静默截断，因此超过 72 字节一律返回 400，不做截断后放行。
- 鉴权失败发生在拦截器，`/api/qa` 也不例外：401/403 是**返回 `SseEmitter` 之前的普通 JSON**，与现有 400/404/409 的同步校验语义一致。
- 令牌校验顺序：头存在 → `Bearer ` 前缀 → 摘要查表 → 未过期 → 账户 `ENABLED`。

### 6.2 登录 / 登出 / 当前账户

| 方法 | 路径 | 入参 | 出参 |
|---|---|---|---|
| POST | `/api/auth/login` | `{username, password}` | `{token, expireTime, user}` |
| POST | `/api/auth/logout` | 无 | `{}` |
| GET | `/api/auth/me` | 无 | `user` |

`user` 结构：

```json
{
  "userId": "3f2b...",
  "username": "admin",
  "displayName": "系统管理员",
  "roles": [{ "roleId": "...", "name": "系统管理员" }],
  "permissions": ["knowledge.documents.view", "..."]
}
```

- `permissions` 为并集；内置管理员角色返回全部权限点。
- 登录成功同时删除该账户已过期会话；不限制并发会话数。
- **登录接口的失败语义（与令牌校验严格区分）**：凭据错误——账户不存在、口令错误、账户已禁用——统一返回 HTTP 401 + `UNAUTHORIZED(401)`，文案「用户名或密码错误」，三者不区分，避免枚举账户与探测状态。`PARAM_INVALID(400)` 只用于报文格式问题（如 `username` 为空、`password` 缺失），**不用于凭据错误**。
- **受保护接口的失败语义**：令牌缺失、格式非法、不存在、已过期、账户已禁用，统一返回 HTTP 401 + `UNAUTHORIZED(401)`，文案「登录已失效，请重新登录」。两者同为 401，但**入口不同、文案不同、前端处理不同**：登录接口的 401 只展示错误，受保护接口的 401 清令牌并跳登录。两处边界不得混用。

### 6.3 账户管理（除「改自己密码」外均需 `settings.accounts.manage`）

| 方法 | 路径 | 入参 | 说明 |
|---|---|---|---|
| GET | `/api/accounts` | 无 | 列表，按 `create_time` 升序 |
| POST | `/api/accounts` | `{username, displayName, password, roleIds[]}` | 新增；口令长度规则见 6.1 |
| PUT | `/api/accounts/{userId}` | `{displayName, roleIds[]}` | 改显示名与角色集合（至少 1 个角色） |
| PUT | `/api/accounts/{userId}/password` | `{newPassword}` | 重置密码；重置后失效该账户全部会话 |
| PUT | `/api/accounts/{userId}/status` | `{status}` | `ENABLED` / `DISABLED`；禁用即失效该账户全部会话；违反 2.2 硬约束返回 400 |
| PUT | `/api/accounts/me/password` | `{oldPassword, newPassword}` | **仅需登录**，无需 `settings.accounts.manage`；校验旧口令，成功后失效该账户其他会话并保留当前令牌 |

账户条目出参：`{userId, username, displayName, roles[], status, createTime}`。

- 路由匹配顺序：`/me/password` 必须排在 `/{userId}/password` 之前匹配。实现时把 `me` 的映射显式声明在 `{userId}` 之前，避免 `me` 被当作 `userId`。

### 6.4 角色与权限点

| 方法 | 路径 | 入参 | 权限 |
|---|---|---|---|
| GET | `/api/roles` | 无 | `settings.roles.manage` 或 `settings.accounts.manage`（账户页需要选择角色） |
| POST | `/api/roles` | `{name, description, permissionCodes[]}` | `settings.roles.manage` |
| PUT | `/api/roles/{roleId}` | `{name, description, permissionCodes[]}` | `settings.roles.manage`，内置角色返回 `ROLE_PROTECTED` |
| DELETE | `/api/roles/{roleId}` | 无 | `settings.roles.manage`，内置或有引用返回对应错误 |
| GET | `/api/permissions` | 无 | `settings.roles.manage` |

角色出参：`{roleId, name, description, builtin, permissionCodes[], userCount}`（`userCount` 为引用账户数）。
权限点出参：`[{code, name, description}]`，供角色页渲染勾选列表。

### 6.5 既有接口的鉴权映射

| 接口 | 权限点 |
|---|---|
| `GET /api/admin/documents`、`GET /{docId}/chunks`、`POST /search` | `knowledge.documents.view` |
| `POST /api/admin/documents`、`DELETE /{docId}` | `knowledge.documents.write` |
| `POST /api/qa`、`GET /api/conversations/**`、`DELETE /api/conversations/{id}` | `knowledge.chat.use` |
| `/api/accounts/**` | `settings.accounts.manage`；`PUT /api/accounts/me/password` 例外，**仅需登录** |
| `/api/roles/**`、`/api/permissions` | 见 6.4 |
| `/api/extract/**`、`/api/draft/**`、`/api/admin/eval/**` | 仅要求登录（无页面入口，不挂权限点） |
| `/api/auth/login`、`/health` | 匿名 |

---

## 7. 存量数据迁移（`dev-user-001`）

### 7.1 启动顺序

1. 建表（`docs/schema.sql`，新库直接生效）。
2. 创建内置角色「系统管理员」（若不存在）。
3. 按 `AUTH_BOOTSTRAP_ADMIN_USERNAME`（默认 `admin`）与 `AUTH_BOOTSTRAP_ADMIN_PASSWORD` 创建初始管理员（若该用户名不存在），并绑定内置角色。
4. 执行存量会话归属迁移。

### 7.2 迁移 SQL（幂等）

```sql
UPDATE conversation
   SET user_id = #{adminUserId}
 WHERE user_id = 'dev-user-001';
```

- **幂等来源**：`WHERE user_id = 'dev-user-001'` 本身。第二次执行命中 0 行，不产生副作用。
- **跳过条件**：第 3 步未创建出管理员（环境变量缺失、或该用户名已存在但未被识别）时，**整段不执行**，只打一条 WARN：`跳过存量会话归属迁移：未找到初始管理员`。
- **执行位置**：应用启动的 bootstrap 流程（`account` 领域服务），SQL 落在 `conversation/mapper/ConversationMapper.xml`，由 `ConversationService` 暴露迁移方法，避免跨层直连数据源。
- **不接受的做法**：`docs/schema.sql` 内执行。该文件只在新库初始化时运行一次，那时管理员还不存在；写进去会在存量库上永久跳过且在全新库上无意义。

### 7.3 已知后果

- 若某台机器上 `dev-user-001` 的会话先被清理、后补管理员，这批数据不会二次迁移（已无痕迹）。可接受。
- 迁移完成后 `conversation.user_id` 中不再存在 `dev-user-001`；前端 `DEV_USER_ID` 常量删除，`X-User-Id` 请求头停用。

---

## 8. 后端改造落位

| 内容 | 落位 |
|---|---|
| 新领域 | `account/`（`controller` / `service`+`impl` / `mapper` / `model/{entity,dto,request,response}`） |
| 认证拦截器 | `common/web/AuthInterceptor`（替换 `UserIdInterceptor`，写入同名 `userId` 属性） |
| 权限点枚举 | `common/constant/PermissionCode`（`code` / `desc` 双字段） |
| 口令编码器 | `config` 下声明 `BCryptPasswordEncoder` Bean |
| 角色状态等字面量 | 收敛到 `common/constant`，禁止散落字符串比较 |
| 会话与口令配置 | `application.yml` 新增 `auth.session-ttl-days: 7`、`auth.bootstrap.admin-username`、`auth.bootstrap.admin-password`；口令仅从环境变量注入，默认空值=不创建 |

对既有代码的影响：

- 删除 `UserIdInterceptor` 及其单测；`WebMvcConfig` 改为注册 `AuthInterceptor` 覆盖 `/api/**` 并排除 `/api/auth/login`、`/health`。
- `QaControllerTest`、`ConversationControllerTest` 等通过 `standaloneSetup(...).addInterceptors(new UserIdInterceptor())` 构造的测试，改为注册 `AuthInterceptor` 并提供合法令牌桩。
- `ConversationController` / `QaController` 的 `@RequestAttribute("userId")` 取值方式不变，业务层零改动。
- `ErrorCode` 新增 6 个枚举（见 6.1）。
- 新增依赖 `spring-security-crypto`（版本由 Spring Boot BOM 管理），**不引** `spring-boot-starter-security`；在 `coding-standards.md` §8 记录该例外理由。

实现要点：

- 硬约束 2 的校验必须同时挂在 `PUT /api/accounts/{userId}/status`（禁用）与 `PUT /api/accounts/{userId}`（角色集合变更）两个入口，按「操作完成后的系统状态」判定；只在禁用接口校验会留下「改角色拿掉管理权限」的锁死漏洞。
- `/api/accounts/me/password` 的映射必须声明在 `/{userId}/password` 之前，避免 `me` 被解析成 `userId`。

---

## 9. 前端改造落位

| 内容 | 落位 |
|---|---|
| 认证上下文 | `src/lib/auth.tsx`（`AuthProvider` + `useAuth`，令牌读写、登录/登出、401 监听） |
| 路由守卫 | `src/components/RequireAuth.tsx`，仅包裹 `AppShell` 内的受保护路由 |
| 登录页 | `src/features/auth/LoginPage.tsx`（不套 `AppShell`） |
| 账户管理页 | `src/features/settings/AccountsPage.tsx` |
| 角色管理页 | `src/features/settings/RolesPage.tsx`（权限点勾选列表自绘，复用 `ui.tsx` 的 `Button` / `Tag` / `ConfirmDialog`） |
| 导航配置 | `AppShell.tsx` 的 `NAV_ITEMS` 改为 `NAV_GROUPS`（含 `children`、`requiredPermission`），并实现展开态与高亮 |
| 身份注入 | `client.ts` 的 `authHeaders()` 改为注入 `Authorization: Bearer`，删除 `X-User-Id` 与 `lib/identity.ts` |
| 接口层 | 新增 `src/api/auth.ts`、`src/api/accounts.ts`、`src/api/roles.ts`；`types.ts` 镜像新契约 |
| mock | `api/mock.ts` 增加登录态与账户/角色假数据；`VITE_USE_MOCK=true` 走本地假登录 |

不新增任何依赖（无组件库、无状态库、无请求库），符合前端 `AGENTS.md` §1。

---

## 10. 票拆分

每票单独 commit、不 push；前后端各自仓库内独立提交。后端每票完成后清理 `target/test-classes` 并跑相关测试，阶段结束跑一次全量 `mvn test`。

### 10.1 后端（6 票）

| 票 | 内容 | 依赖 | 完成判据 |
|---|---|---|---|
| B1 | `docs/schema.sql` 新增 5 张表；`PermissionCode` 枚举；`account` 领域骨架与 Mapper | — | 新库可建表；Mapper XML 可解析（契约测试） |
| B2 | 登录/登出/me；BCrypt；`AuthInterceptor`；`ErrorCode` 扩展；启动 bootstrap（内置角色 + 初始管理员） | B1 | 无令牌 401、正确口令拿到令牌、登出后令牌失效 |
| B3 | 账户管理接口（列表/新增/改资料/改角色/重置密码/改自己密码/启停）与硬约束 | B2 | 2.2 的 5 条硬约束各有对应用例 |
| B4 | 角色与权限点接口（含内置保护、引用计数） | B2 | 内置角色三类写操作均被拒；有引用不可删 |
| B5 | 存量会话迁移 + 删除 `UserIdInterceptor` + 改造受影响的既有测试 | B2 | `dev-user-001` 行归属变为管理员；重复执行 0 行变更；全量测试通过 |
| B6 | 文档同步（第 12 节）+ 新增 ADR | B1–B5 | 文档与实现一致，ADR 入库 |

### 10.2 前端（5 票）

| 票 | 内容 | 依赖 | 完成判据 |
|---|---|---|---|
| F1 | `auth.tsx`、`RequireAuth`、登录页、`client.ts` 认证头改造、401 处理 | B2 可用 | 未登录跳转、登录回跳、401 自动登出 |
| F2 | 导航一级/二级重构、路由迁移与旧链接重定向、面包屑、按权限过滤菜单 | F1 | 四个页面可达；无权限菜单隐藏 |
| F3 | 账户管理页 | F2 + B3 | 增改、改角色、重置密码、启停均可用，约束有提示 |
| F4 | 角色管理页 | F2 + B4 | 增改删、权限勾选、内置角色只读 |
| F5 | mock 登录、`AGENTS.md` 同步、前端验收脚本更新 | F1–F4 | mock 模式可完整走通登录与四个页面 |

---

## 11. 验收

1. 新环境仅设 `AUTH_BOOTSTRAP_ADMIN_PASSWORD` 即可启动，用 `admin` 登录进入系统。
2. 未登录访问 `/knowledge/documents` 跳登录并在登录后回跳；直接调 `/api/qa` 得到普通 JSON 401（不是 SSE 流）。
3. 四个页面按 2.1 的层级与路径可达；`/documents`、`/chat` 自动跳新路径；面包屑显示「一级 / 二级」。
4. 新建角色「文档只读」，只勾 `knowledge.documents.view`；分配给新账户后：该账户只看到「知识库 > 文档管理」，进入对话页被拒（403 文案，不跳登录）。
5. 禁用该账户后，其已有令牌立即失效；重新登录提示「用户名或密码错误」。
6. 内置角色「系统管理员」无改名/改权限/删除入口；后端对三类请求均返回 `ROLE_PROTECTED`。
7. 最后一个具备账户管理能力的账户，既不能被禁用，也不能被改成不含管理权限的角色集合（后端 400 + 前端提示）。
8. 存量库升级后，原 `dev-user-001` 的会话出现在管理员的对话列表中。
9. 全量 `mvn test` 全绿；前端 `npm test`、`npm run typecheck`、`npm run build` 全绿。

---

## 12. 需同步改写的既有文档

| 文件 | 改动点 |
|---|---|
| 前端 `AGENTS.md` | §1 路由从「`/documents`、`/chat`」改为四个层级路由 + `/login`；§2 目录结构新增 `features/auth`、`features/settings`、`lib/auth.tsx`、`components/RequireAuth.tsx`，删除 `lib/identity.ts`；§5「X-User-Id 统一注入」改为「Authorization 统一注入」；§7 删除「页面不提供登录、用户菜单、设置」并改为登录与权限相关红线；§10 手工验收路径补登录步骤 |
| `docs/prd/multi-turn-backend.md` | §1.2「多用户鉴权与真实登录体系」中标为已被本文取代；§2.1 `userId` 行的「前端生成、`X-User-Id` 传递」改为「后端令牌解析」 |
| `docs/prd/multi-turn-frontend.md` | §2.1 `userId` 行、§5「所有请求必须携带 `X-User-Id`」、验收第 25 条同口径改写 |
| `docs/agents/coding-standards.md` | §0.1 包结构树新增 `account/`；§8 记录「引入 `spring-security-crypto`」的例外理由；§5 错误语义补充 401/403 归位规则 |
| `GLOSSARY.md` | 补入第 4 节的 6 个术语；**并更新「错误码（ErrorCode）」整行**——该行现仅列 5 个码，而 `ErrorCode` 枚举当前已有 10 个，本文再增 6 个（`UNAUTHORIZED` / `FORBIDDEN` / `USERNAME_EXISTS` / `ROLE_NAME_EXISTS` / `ROLE_IN_USE` / `ROLE_PROTECTED`），需改为与枚举一致的完整清单（共 16 个），不再保留截断示例 |
| `docs/schema.sql` | 新增 5 张表与全部 `COMMENT ON`；`conversation.user_id` 注释改为指向 `app_user.user_id` |
| `README.md` | 若决策表列有鉴权相关数字（令牌有效期、权限点数量），同步更新 |

新增文档（非改写）：

- `docs/adr/0019-account-session-and-roles.md`：记录「不引 Spring Security 全家桶、不用 JWT、角色是数据、`user_id` 用 UUID 主键」四项架构取舍。按 `coding-standards.md` §10.4，架构级取舍必须有 ADR，建议与 B1 同批提交。

---

## 13. 风险与遗留

| 项 | 说明 | 处置 |
|---|---|---|
| 令牌存 localStorage | XSS 下可被读取（HttpOnly Cookie 可规避，但会引入跨域 credentials 配置） | 内部工具接受；后续如需强化再切 Cookie |
| BCrypt 72 字节上限 | 超长口令会被静默截断（72 个中文字符约 216 字节，按字符数校验挡不住） | 按 §6.1 的字节口径校验，超 72 字节直接 400 |
| 单实例会话 | 多实例部署时令牌表可共享，但登录清理逻辑为进程内 | 本期单实例；扩容时改为共享库即可，方案本就基于 DB |
| 前端无组件库 | 角色权限勾选、账户表单需自绘 | 复用既有 `ui.tsx`、`Drawer`、`ConfirmDialog`，不引依赖 |
| 历史 PRD 中的旧口径 | `multi-turn-*` 内仍写着 `X-User-Id` | 按第 12 节就地改写，不保留双份说明 |
