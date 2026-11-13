# 01: 登录闭环（数据底座、初始管理员、登录与全局鉴权）

**What to build:** 系统首次启动即可登录：账户、角色、账户角色、角色权限、会话五张表；权限点目录；内置「系统管理员」角色与初始管理员自举；登录、登出、当前账户；所有业务接口改为令牌鉴权；架构取舍记录成 ADR。

**Blocked by:** None（可立即开始）

**Status:** done（2026-11-13 收尾回写；验证依据见文末）

**发版说明（边缘 2 的处理方式）：** 本票移除旧身份请求头并强制令牌，后端一旦上线旧前端即不可用。因此**本票与 02 票必须同一发版窗口上线**；只合代码、不部署到共享环境不算违规。不保留旧请求头的过渡兼容（PRD 明确不做两套身份并存），合并发布是本次选定的处理方式。

- [x] 五张表按 spec 建好并带表/列注释；用户名与角色名各有 `lower()` 函数唯一索引；会话表只存令牌摘要；会话表的 `user_id` 列注释指向账户表主键
- [x] 未提供初始管理员口令时应用正常启动，只记录一条警告，不创建任何账户
- [x] 提供口令时创建内置角色、初始管理员并完成绑定；重复启动不重复创建、不覆盖已有口令
- [x] 权限点目录为固定 5 个权限点，未知权限码在解析时被拒绝而非静默忽略
- [x] 正确凭据登录返回令牌、到期时间与账户信息（角色与权限点并集）
- [x] 账户不存在、口令错误、账户已禁用三种情况都返回 401 且文案完全一致
- [x] 无令牌访问流式问答接口时返回普通 JSON 401，不会建立事件流
- [x] 令牌过期或对应账户被禁用后，下一次请求即 401；登出只失效当前令牌
- [x] 角色权限调整后无需重启，下一次请求按新权限判定；缺权限返回 403
- [x] 原有身份请求头不再被接受，旧身份拦截器与其单测删除，受影响的控制器测试改为提供合法令牌桩
- [x] 建表脚本契约测试与 Mapper 可解析性测试通过
- [x] 架构取舍（自管会话、角色是数据、账户主键用 UUID、不引 Security 全家桶、不用 JWT）记录为 ADR-0019
- [x] 后端全量测试通过

**收尾回写（2026-11-13）**：`mvn test` 343 用例全绿；真环境验证缺口令跳过、已存在跳过、无令牌流式接口普通 JSON 401、禁用账户后令牌 401。令牌过期路径仅单测覆盖，未做真环境验证。

---

## 实现落位（决策完备，待确认后执行）

### 新增 / 改动文件

| 文件 | 改动 |
|---|---|
| `docs/schema.sql` | 追加 `app_user` / `app_role` / `user_role` / `role_permission` / `user_session`；两个 `lower()` 函数唯一索引；全部表/列 `COMMENT ON`；`conversation.user_id` 注释改为「值为 `app_user.user_id`」 |
| `docs/adr/0019-account-session-and-roles.md` | 新增 ADR：不引 Security 全家桶、不用 JWT、角色是数据、`user_id` 用 UUID 主键 |
| `pom.xml` | 仅新增 `spring-security-crypto`（版本由 Boot BOM 管理） |
| `common/constant/PermissionCode.java` | 5 个权限点 `code`/`desc` 枚举；`fromCode` 未识别直接拒绝 |
| `common/constant/AccountStatus.java` | `ENABLED` / `DISABLED` 枚举 |
| `common/constant/AuthConstants.java` | `userId` 属性名（沿用）、令牌摘要属性名、`Bearer ` 前缀、内置角色名、bootstrap 默认显示名 |
| `common/exception/ErrorCode.java` | 新增 `UNAUTHORIZED(401)` / `FORBIDDEN(403)` / `USERNAME_EXISTS(409)` / `ROLE_NAME_EXISTS(409)` / `ROLE_IN_USE(409)` / `ROLE_PROTECTED(400)` |
| `common/web/AuthInterceptor.java` | 解析 `Authorization: Bearer` → 查会话 → 过期/禁用 401；写入 `userId` 与令牌摘要；按「方法 + 路径 → 权限点」表校验，缺权限 403 |
| `common/web/WebMvcConfig.java` | 改注册 `AuthInterceptor`，覆盖 `/api/**`，排除 `/api/auth/login` |
| `common/web/UserIdInterceptor.java` + 其单测 | 删除 |
| `config/AuthConfig.java` | `BCryptPasswordEncoder` Bean |
| `config/AuthBootstrapRunner.java` | `ApplicationRunner`，启动期调用 bootstrap |
| `account/controller/AuthController.java` | `POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me` |
| `account/service/AuthService.java` + `impl/AuthServiceImpl.java` | 登录（BCrypt/令牌摘要/过期会话清理）、登出（只删当前令牌）、当前账户、`authenticate(token)`（每次请求重算角色与权限并集，内置角色短路全权限） |
| `account/service/AuthBootstrapService.java` + `impl/AuthBootstrapServiceImpl.java` | 幂等创建内置角色与初始管理员；缺口令只 WARN 不建号；已存在不覆盖口令；绑定内置角色幂等 |
| `account/mapper/AppUserMapper.java` / `AppRoleMapper.java` / `UserRoleMapper.java` / `UserSessionMapper.java` + `resources/mapper/*.xml` | 用户按 `lower(username)` 查、插入；角色按 `lower(name)` 查、插入；`user_role` `ON CONFLICT DO NOTHING`；会话插入/按摘要查（连账户状态）/按摘要删/清过期 |
| `account/model/entity/AppUserEntity.java` / `AppRoleEntity.java` / `UserSessionEntity.java` | DB 行对象（record） |
| `account/model/dto/AuthenticatedAccountDTO.java`、`RoleBriefDTO.java` | 认证上下文载体（userId / username / displayName / roles / permissions） |
| `account/model/request/LoginRequest.java` | `{username, password}` |
| `account/model/response/LoginResponse.java` / `AuthAccountResponse.java` / `RoleBriefResponse.java` | 登录与 me 的出参 |
| `application.yml` | `auth.session-ttl-days`（默认 7）、`auth.bootstrap.admin-username`（默认 `admin`）、`auth.bootstrap.admin-password`（默认空） |

### 行为口径

- 登录失败（账户不存在 / 口令错 / 已禁用）统一 401 + `UNAUTHORIZED` + 「用户名或密码错误」；报文缺失/空白才 400。
- 受保护接口失败统一 401 + 「登录已失效，请重新登录」；缺权限 403 + 「无访问权限」。
- 令牌：`SecureRandom` 32 字节 Base64URL，库中只存 SHA-256 十六进制；有效期固定 7 天。
- 鉴权路径表：`GET /api/admin/documents`、`GET /api/admin/documents/*/chunks`、`POST /api/admin/documents/search` → 查看文档；`POST /api/admin/documents`、`DELETE /api/admin/documents/**` → 维护文档；`POST /api/qa`、`/api/conversations/**` → 使用知识库对话；其余 `/api/**` 仅需登录。
- `ConversationController` / `QaController` 的 `@RequestAttribute("userId")` 不变。

### 测试（先红后绿）

| 测试 | 覆盖 |
|---|---|
| `SchemaContractTest`（扩展） | 5 张表、`lower()` 唯一索引、注释、`conversation.user_id` 注释 |
| `account/mapper/AccountMapperXmlContractTest` | 4 个 XML 可解析、语句齐全、参数可绑定 |
| `account/service/impl/AuthServiceImplTest` | 三类凭据错误同文案、令牌摘要、过期/禁用 401、权限并集、内置短路、未知权限码拒绝、登出只删当前令牌 |
| `account/service/impl/AuthBootstrapServiceImplTest` | 缺口令跳过、已存在不覆盖、角色与绑定幂等 |
| `common/web/AuthInterceptorTest` | 无头/坏前缀/未知令牌/过期/禁用 401，缺权限 403，合法令牌写入 `userId` |
| `account/controller/AuthControllerTest` | 登录成功出参、三类失败同文案、登出、me |
| `QaControllerTest` / `ConversationControllerTest`（改造） | 注册 `AuthInterceptor` + 合法令牌桩；`/api/qa` 无令牌返回普通 JSON 401 |
| `common/constant/PermissionCodeTest` | 5 个码固定、未知码拒绝 |

### 验证步骤

1. `mvn test` 全绿，记录用例数与基线对比。
2. `Get-Content -Raw -Encoding UTF8 docs/schema.sql | docker exec -i lender-pg psql -U postgres -d lender_assistant` 重跑成功、存量行数不变。
3. 带 `AUTH_BOOTSTRAP_ADMIN_PASSWORD` 启动一次：建出内置角色与 `admin`；无口令再启动一次：不建号、只 WARN；重复启动不重复建。
4. 单独提交 `feat(auth): ...`，不 push。
