# 05: mock 业务工具与默认关闭开关

**阶段:** P2 计划与工具
**Blocked by:** 04
**Status:** ready-for-agent

**范围:** `customer.profile`、`lender.product` 两个 mock 工具、本地 JSON 数据、开关与白名单。

**要做的事:** 计划可以同时利用知识库与「客户/产品」两类业务信息；生产默认不启用，联调时通过开关打开。

**涉及文件/类:** `MockCustomerProfileTool`、`MockLenderProductTool`、`resources/mock/customer-profiles.json`、`resources/mock/lender-products.json`、`rag.plan.tools.mock-enabled`、`rag.plan.allowed-tools`。

- [ ] `customer.profile` 参数 `{customerId}` 必须以 `C` + 5 位数字匹配，否则该步失败 `ARGS_INVALID`
- [ ] `lender.product` 参数 `{productCode}` 必填；返回费率、期限、放款条件、支持的接口类型摘要
- [ ] 两个工具均读本地 JSON，不发网络请求、不需要密钥
- [ ] `chunkIds` 恒为空，不参与 sources 并集
- [ ] 超时 2s、不重试；数据缺失返回失败而非抛异常
- [ ] `mock-enabled=false` 时不注册、不在白名单；开启后白名单追加两个工具
- [ ] 单测：参数合法/非法、数据缺失、开关关闭时不可见

**不做什么:** 不接真实业务系统；不改 `kb.search`；不做工具级缓存。
