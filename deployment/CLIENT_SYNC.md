# Bash 客户端同步编排（本地 HTTP 验证，不代表云端全流程）

入口：`scripts/tlq-sync.sh`；标准库 Python 辅助程序：`scripts/tlq_client.py`。
Bash 控制顺序，复用现有 Java BatchMain 解析 CSV 记录并分批，Python 负责 HTTP、内存 SigV4 和结构化指标。
本次没有调用 AWS、连接数据库或部署。Load/Query 云端接口尚未部署；不能将本地 HTTP 测试视为云端完整 TLQ 已验证。

## 执行约定

1. 每轮自动创建独立 UTC+UUID run_id 和新输出目录，不覆盖上轮数据，不提供续跑/重用 run_id。
2. 按逻辑 CSV 记录分批，默认最多 200 条，每批包含完整表头。复用 BatchMain，还逐条核对分批结果与原源文件顺序、数量。
3. 每批按顺序 POST CSV 到 Transform，保存响应 JSON，核对条数及与本地转换结果的全部字段一致后，原字节转发给 Load。
4. Transform 与 Load 均必须 HTTP 200；Load 响应必须有整数 committedRecords，且等于该批记录数。
5. 所有批次 Load 确认成功后才发送 Query；Bash 顺序控制之外，辅助程序再次核对清单及每批 Load 成功指标。
6. 任一步失败立即停止，禁用 HTTP 自动重定向，不自动重试任何 HTTP POST，尤其不重试 Load。
7. Load 请求已发送后的超时、断连、非 200、坏响应或提交数不符均保守标记 UNKNOWN_STOP_RUN；不宣称已回滚，不自动重发或继续 Query。
8. Query 必须返回相同 runId 和 results 数组。核对请求指标字段；不带过滤且请求 count 时，所有分组 count 总和必须等于源记录数。
9. 200 是初始记录数上限，不是 payload 字节保证。遇到应用/平台超限停止当前轮，调整批大小后开启新轮，不自动修改已运行批次。

## Windows Git Bash

需要已有 Bash、Python 3.10+ 标准库和 Java 21；不需要 jq、curl、boto3 或额外 Python 包。
Java/Python 必须能由 Git Bash 找到；如 PATH 不合适，可选择现有可执行文件：

```bash
export TLQ_PYTHON='C:/Python314/python.exe'
export TLQ_JAVA='C:/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot/bin/java.exe'
```

这些不是凭证，不要在命令中添加访问密钥。默认 JAR 为项目 target/client-sync-tlq.jar；先按现有说明运行 mvn clean package。
脚本通过 cygpath 规范化文件路径，支持空格路径；.gitattributes 要求 .sh 使用 LF。
辅助程序生成的分批 TSV 也强制 LF，避免原生 Python CRLF 被 Bash 当作文件名的一部分。
无需 chmod，可通过 `bash scripts/tlq-sync.sh ...` 执行。

## 本地 HTTP 自动测试（现在可运行，无 AWS/真实数据库）

在项目根目录 Git Bash 执行：

```bash
python -m unittest discover -s scripts/tests -v
```

Windows Python 不在 PATH 时可替换为上面 TLQ_PYTHON；测试通过 TLQ_JAVA 选择 Java。
测试会自动启动 127.0.0.1 临时 HTTP 服务，结束后关闭，不注册系统服务。
真实执行 Bash、Java 分批和转换；HTTP Load/Query 使用内存测试服务，不接 MySQL。
输入为 401 条含中文、逗号、双引号、字段内换行和重复行的 75 列 CSV，默认分成 200/200/1。
成功路径验证每批 Transform→Load 的原字节传递，最后才 Query；另验证可配置批大小和每轮 run_id 不同。
失败路径包括 Transform HTTP/条数/内容错误，Load HTTP/提交数/提交后断连/307 重定向，Query HTTP/runId/总数错误，以及非回环无认证地址拒绝。
全部失败用例均要求立即停止、不重试、不提前 Query，并保留失败轮摘要。
签名与 root 防护使用仅内存生成的虚拟凭证和替身 AWS 命令测试，没有访问 AWS。

本地测试服务手动接入格式示意（需已有兼容本地服务，不会自动启动后端或建库）：

```bash
bash scripts/tlq-sync.sh \
  --csv 'C:/path/to/1K_RECORDS.csv' \
  --transform-url 'http://127.0.0.1:8080/transform' \
  --load-url 'http://127.0.0.1:8080/load' \
  --query-url 'http://127.0.0.1:8080/query' \
  --query-file 'C:/path/to/query.json' \
  --auth none --batch-size 200
```

none 模式仅允许 http://127.0.0.1、localhost 或 IPv6 回环；不允许外部地址或混用远程无认证接口。
默认 auth 为 aws-iam，不会悄悄回退 none。

## AWS_IAM 配置（仅使用方法，本次不执行）

支持显式 --profile、--region 和按步骤覆盖的 --transform-region、--load-region、--query-region。
所有 AWS CLI 调用均显式传 --profile 和 --region；继承的静态密钥、默认 profile、角色环境变量不会作为隐式回退来源。
每次签名请求都核对 STS 身份不是 root，随后由相同 profile/region 获取带 SessionToken 的临时凭证。
使用此前真实 Transform 验证的 SigV4 方式（服务 lambda、正文 SHA256、UTC 日期、session token），额外签入 X-TLQ-Run-Id。
凭证仅在 Python 内存及请求头中使用，不通过 Bash、命令参数、文件、日志或 stdout 传递。
AWS CLI 原有登录缓存仍由 CLI 自身管理；脚本不导出新的凭证文件，也不自动登录或扩大权限。
不记录授权头、session token 或完整 AWS CLI/HTTP 异常消息。
AWS_IAM 地址必须为与签名区域一致的 HTTPS Lambda Function URL；不支持带账号密码、查询参数或片段的 URL。

未来三个接口均准备好且另行授权后，可用：

```bash
bash scripts/tlq-sync.sh \
  --csv 'test-data/1K_RECORDS.csv' \
  --transform-url "$TRANSFORM_URL" --load-url "$LOAD_URL" --query-url "$QUERY_URL" \
  --profile tcss562-dev --region us-east-2 --transform-region us-west-2 \
  --query-file 'query.json' --batch-size 200
```

以上示例中的 Load/Query us-east-2 只是可配置示例，不能视为区域/网络/权限已验证；现有开发权限仅覆盖 Transform。
签名 profile 登录、所有所需调用权限、凭证未过期及网络可达性需未来真实验证，不会回退到 root。

## 查询参数与其他配置

query.json 例如：

```json
{"filters":{"year":"2018"},"groupBy":["state"],"metrics":["count","sum","avg"]}
```

省略 --query-file 时，默认无过滤/分组，metrics 为 count、sum、avg；非法字段在 HTTP 之前拒绝。
需要 count 时客户端记录 matchedRecords；仅请求金额指标时记录 groupCount，不假装知道过滤后的明细记录数。
--output-root 默认 test-output/runs（Git 已忽略）；每轮创建新子目录。
--jar 可选择 JAR；TLQ_JAVA、TLQ_PYTHON 选择程序。--timeout-seconds 默认 60，必须为正整数且不超过 3600。
所有 HTTP 为单次尝试；即使超时也不在当前轮自动重试。

## 每轮输出与耗时含义

- run.json：run_id、开始时间、非敏感配置及状态；不保存任何凭证。
- batches/：每批完整 CSV、既有本地转换 JSON 及 manifest.json。
- responses/：Transform 原始返回 JSON、Load 确认 JSON、Query 返回 JSON；错误响应也保存（客户端字节预算以内）。
- query-request.json：本轮查询参数，不含凭证。
- metrics.jsonl：每步 STARTED / FINISHED 事件，stepId 关联。
- summary.json：整轮 PASSED/FAILED、退出码、整轮耗时、已确认提交数、成功批次及失败/未完成步骤。

HTTP 步骤记录 httpStatus（无响应为 null）、请求/响应字节数、预期条数、实际条数或 committedRecords；Query 记录分组数及可用的匹配记录数。
elapsedMs 是辅助程序内该步完整耗时；authElapsedMs 是 STS/临时凭证获取/签名开销；httpElapsedMs 是 HTTP 往返和读响应；validationElapsedMs 是成功后的内容验证。
失败步骤仍有总 elapsedMs，未走到的子阶段耗时不会伪造为成功值。
totalElapsedMs 从分配 run 输出目录后计时，包含配置验证、分批、各程序启动、认证、HTTP、验证及 Bash 编排间隔。
它不是 Lambda Duration，也不包含任何 CloudWatch 查询；比较性能时不能混淆这些口径。

辅助进程逐请求获取临时凭证并检查身份，故未来 AWS 模式存在认证开销，已单独记录；本地 auth none 的耗时不能当作云端耗时。
EXIT、INT、TERM 会尽力保存失败摘要。SIGKILL/断电无法保证最终落盘，保留的 RUNNING 或未完成 Load 必须按不完整/提交结果不确定处理，禁止续跑。
脚本初始化前的参数语法错误、无法启动 Python 或输出目录不可写时，无法保证创建实验报告；会非零退出，不发送请求。

## 本次验证边界

本地 HTTP 编排、顺序、传递及失败停止通过后，仍不等于 Aurora、云端 Load/Query、IAM 三接口全链路或正式性能/成本实验已验证。
原本地 MySQL 集成报告继续单独保留。本次不删除数据库数据，不创建资源、不部署、不提交或推送。

需要以实际三个 handler 和真实 MySQL 运行整条链路时，使用单独的 [本地真实完整链路入口](LOCAL_REAL_CHAIN.md)，由用户隐藏输入密码后启动，不能用本页内存替身结果替代。
