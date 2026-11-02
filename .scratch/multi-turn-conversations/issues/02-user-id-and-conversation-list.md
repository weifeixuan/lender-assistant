# 02: 用户身份解析与会话列表

**What to build:** 前端携带 `X-User-Id` 后，可以通过 `GET /api/conversations` 看到属于自己的、未删除的会话，最近更新的排在最前；未带或带非法头时得到可读的 400，而不是 500。会话条目包含会话 id、标题、创建时间、更新时间。

**涉及层次:** Web 拦截器与 MVC 配置、conversation 的 controller/service/mapper、MockMvc 测试。

**Blocked by:** 01

**Status:** ready-for-agent

**Estimate:** 1–1.5 小时

- [ ] 合法 `X-User-Id` 请求返回 `200`，响应体为 `{code: 200, msg: "ok", data: [...]}`
- [ ] 条目字段为 `conversationId`、`title`、`createTime`、`updateTime`；时间格式为 `YYYY-MM-DD HH:mm:ss`
- [ ] 只返回该 `userId` 下 `status=ACTIVE` 的会话；其他用户的会话不出现
- [ ] 排序为 `update_time DESC, id DESC`；同秒创建时后插入的会话在前
- [ ] 无会话时返回 `200` + `data: []`，不是 404
- [ ] `X-User-Id` 缺失、空白、超长或含非法字符时返回 `400 PARAM_INVALID`，响应为普通 JSON
- [ ] 拦截器把合法 `userId` 写入 request attribute，controller 通过 request attribute 取值，不使用 ThreadLocal
- [ ] MockMvc 测试覆盖成功、空列表、非法头三类场景
