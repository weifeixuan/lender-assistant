# ELK 观测栈（elk/）

本目录是 lender-assistant 的日志观测栈：**Elasticsearch + Kibana + Filebeat**，Filebeat 直连 ES，**不经过 Logstash**。设计为在 **Ubuntu (WSL2) 终端**中运行（引擎仍是 Docker Desktop，WSL Integration 开关已开）。

## 一、架构与选型

```text
应用（java -jar 或容器）── logback StructuredLogEncoder ── lender-assistant.log（单行 logstash JSON）
        │                                          │
        │ E:/project/logs（本地跑，LOG_PATH 默认）   │ 项目 ./logs（容器跑，compose 挂载落盘）
        ▼                                          ▼
        Filebeat（两个 filestream 输入，json.keys_under_root 直通）
        ▼
        Elasticsearch 单节点（索引 lender-assistant-logs-yyyy.MM.dd）
        ▼
        Kibana（127.0.0.1:5601，建数据视图 lender-assistant-logs-* 后按 level 检索）
```

**为什么不用 Logstash**：应用日志在 logback 写盘那一刻就已是 logstash JSON——grok（文本解析）和 multiline（堆栈跨行拼接）这两个纯文本时代的补救工序被整体消解。Filebeat 只做搬运，ES ingest 都不用配。多系统扩展时同理：每系统一份 Filebeat 配置 + 一个 `系统名-logs-*` 索引，直接发同一个 ES；只有改不动的纯文本老系统才值得单独开 Logstash 通道。

**为什么 ES 是单节点 + 关认证**：开发期观测工具，数据可随时丢弃重建，`xpack.security.enabled=false` 换取零配置；端口只绑 `127.0.0.1` 不对外。上生产时这些全部要反转（认证开、集群化、托管服务优先）。

**索引命名约定（B 方案）**：日志 `lender-assistant-logs-*`；将来业务数据进 ES 统一 `lender-assistant-business-*`。filebeat.yml 里 `setup.template.*` 注册索引模板、`setup.ilm.enabled: false` 关闭 ILM——自定义索引名不吃默认策略，旧索引手动清（见下文维护节）。

## 二、运行方式（Ubuntu）

```bash
cd /mnt/e/project/lender-assistant
docker compose -f elk/docker-compose.yml up -d
```

三个容器全部起来后：ES 健康检查通过 → Kibana 就绪 → Filebeat 开始增量采集（**只读当前文件新增行，历史日志不回灌**）。

从 Windows 终端跑也可以，但 `docker-compose.yml` 里宽日志目录默认 `/mnt/e/project/logs` 是 Ubuntu 视角路径，需覆盖：

```powershell
$env:WIDE_LOGS_DIR="E:/project/logs"; docker compose -f elk/docker-compose.yml up -d
```

**停与清**：

```bash
docker compose -f elk/docker-compose.yml down        # 停服务，保留 ES 数据与采集进度
docker compose -f elk/docker-compose.yml down -v     # 连数据卷一起删（ES 索引全部丢失）
```

## 三、验证全链路

```bash
# 1. ES 健康
curl -s http://127.0.0.1:9200/_cluster/health | grep -o '"status":"[a-z]*"'

# 2. 起一次应用（java -jar 或主 compose），发任意请求后看索引是否出现
curl -s http://127.0.0.1:9200/_cat/indices/lender-assistant-logs-*?v

# 3. 看到文档即链路通
curl -s "http://127.0.0.1:9200/lender-assistant-logs-*/_search?size=1&sort=@timestamp:desc" | head -50
```

Kibana 侧（浏览器开 `http://127.0.0.1:5601`）：

1. Stack Management → Data Views → Create data view
2. Pattern 填 `lender-assistant-logs-*`，时间字段选 `@timestamp`
3. Discover 里选这个视图，直接过滤 `level: ERROR`，或搜 `docId` / `chunkId` 串链路

## 四、内存预算（8GB 主机适配）

`.wslconfig` 已设 `memory=5GB` 上限（WSL2 全局：Ubuntu + docker-desktop 共用一个 VM）。本套预算：

| 组件 | 占用 |
|---|---|
| Elasticsearch | ~1GB（堆 512MB + 堆外） |
| Kibana | ~0.8GB（mem_limit 1g 封顶） |
| Filebeat | ~0.1GB |
| pg + 应用容器（主 compose） | ~1GB |
| **合计** | **~3GB / 5GB，有余量** |

拉整套 ELK 前留意 Windows 侧（浏览器、IDE）的占用。

## 五、常见问题

0. **已踩过的两个坑（2026-11-19 实战）**：
   - filestream 输入必须用 `parsers.ndjson` 做 JSON 解析——旧 `json.keys_under_root` 选项属于废弃的 log 输入，filestream 会**静默忽略**，结果整行 JSON 塞进 message 字段。filebeat.yml 已用正确写法。
   - 自定义 `output.elasticsearch.index` 落地后实际是 **data stream**（backing index 带 `.ds-` 前缀）——这是 filebeat 8.x 注册的模板默认开启 data_stream 的结果，功能无损：Kibana 数据视图填 `lender-assistant-logs-*` 可直接匹配，按天分节、`level` 聚合都正常。若坚持要普通索引需 `setup.template.data_stream` 相关配置，开发期没必要。
   - 从 **Windows 终端**起这套 compose 时，`/mnt/e/...` 不会被翻译（那是 Ubuntu 客户端的待遇），wide 日志挂载会变成空目录——必须 `WIDE_LOGS_DIR=E:/project/logs` 覆盖（compose 顶部变量已支持）。

1. **镜像拉不动**：`docker.elastic.co` 国内直连慢或失败时，用镜像站前缀：
   ```bash
   REGISTRY_PREFIX=docker.1ms.run/ docker compose -f elk/docker-compose.yml up -d
   ```
   若该镜像站不支持 elastic 全路径代理，换其他支持 `docker.elastic.co/...` 路径代理的镜像站。
2. **ES 起不来报 `max virtual memory areas`**：内核参数不满足，在 Windows PowerShell 执行
   `wsl -d docker-desktop sysctl -w vm.max_map_count=262144`（Docker Desktop 新版通常自带满足，先看报错再说）。
3. **Filebeat 报配置文件权限**：compose 的 `--strict.perms=false` 已处理（Windows 盘 bind mount 权限是 0777，filebeat 默认拒绝）。
4. **Kibana 里部分文档带 `_json_parse_failure`**：这些是 `./logs` 里历史纯文本旧日志行（日志 JSON 化改造前的产物），属预期；不想要就在 Kibana 过滤掉，或清理旧文件内容。
5. **ES 数据在哪**：named volume `elk_es_data`（Docker Desktop 的 WSL2 虚拟盘内），不在项目目录；删它用 `down -v` 或 `docker volume rm elk_es_data`。

## 六、维护

**旧索引清理**（ILM 已关，按需手动）：

```bash
# 列出按天索引
curl -s "http://127.0.0.1:9200/_cat/indices/lender-assistant-logs-*?v"
# 删某天之前（示例）
curl -s -X DELETE "http://127.0.0.1:9200/lender-assistant-logs-2026.11.1*"
```

**改采集范围**：只动 `filebeat.yml` 的 `filebeat.inputs.paths`，改完 `docker compose -f elk/docker-compose.yml restart filebeat`。

**升级版本**：只改 `docker-compose.yml` 顶部 `ELK_VERSION` 默认值，三服务同步升。

**与项目规范的边界**：本目录是部署配置，不受 coding-standards.md 的 Java 分层/SQL 约束（§6.1 的"SQL 只在 mapper"约束 Java 代码）；将来业务数据同步进 ES（Logstash JDBC 拉取或应用双写）时，同步逻辑按规范落位——路径 B 的 ES 客户端归 `client/`，只被 service 调用。
