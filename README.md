# TCSS-562-CVS_AWS

客户端同步 TLQ 项目。实现 Java 21 CSV → JSON、本地分批及 Transform Function URL handler（已部署并完成一次真实 200 条批次验证）。

## 本地运行（Git Bash）

需要 Java 21 和 Maven 3.9+。首次构建需下载 Maven 依赖。

```bash
mvn clean package
java -jar target/client-sync-tlq.jar "/c/path/to/input.csv" > output.json
```

如果 Windows Java 无法识别上述路径，可使用 `C:/path/to/input.csv`。
JSON 输出到 stdout，错误输出到 stderr；成功退出码为 0，转换失败为 1，参数错误为 2。
Shell 重定向会提前创建或清空输出文件，请使用新的输出文件名，不要指向输入 CSV。
转换完成并序列化成功后才输出 JSON；大文件当前整批存入内存。

## 暂定兼容规则

以队友仓库提交 [de1d217](https://github.com/passakornsdev/tlq-pipeline-with-aws/commit/de1d217918cf98366db3c1df12b6b63b06d3aa60)
的 75 列结构为基准，固定映射见 `PaymentSchema.java`。

- UTF-8 CSV；第一行表头的名称、大小写和顺序必须完全一致。
- 每条 CSV 记录必须有 75 列，按 CSV 记录解析，支持带引号的字段内换行。
- 支持引号、引号内逗号和双引号转义。
- 输出为 JSON 对象数组，每条记录包含全部 75 个固定驼峰字段。
- 所有值保留为字符串，空单元格为 `""`，不 trim、不转换金额或日期、不去重。
- 不忽略空白数据行；缺失表头、错误表头、坏行或列数错误使整次转换失败。
- 仅表头的文件输出 `[]`；不接受 UTF-8 BOM 表头。
- 176 列的 100 CSV 不在支持范围内；完整 1K、10K 已验证为相同的 75 列结构。

这些规则用于当前兼容实现；数据库计划为 Aurora MySQL，最终业务转换、引擎版本和实例规格仍未确定。
提供已部署的 Transform Lambda HTTP 接口；Load、Query 核心与 HTTP handler 已在本地实现但未部署，SES、S3 触发器和异步编排尚未实现。

## 验证

`mvn test` 检查引号解析、75 字段输出、字符串类型及前导零保留、空值、表头和坏行拒绝行为。

## 本地按记录分批

构建后在 Git Bash 运行（输出目录必须是新目录）：

```bash
java -cp target/client-sync-tlq.jar edu.uw.tcss562.tlq.BatchMain 1K_RECORDS.csv test-output/batches-1k
java -cp target/client-sync-tlq.jar edu.uw.tcss562.tlq.BatchMain 10K_RECORDS.csv test-output/batches-10k 200
```

末尾批大小参数可省略，默认每批最多 200 条，必须为正整数。每批完整保留 75 列表头。
按逻辑 CSV 记录切分，支持引号内逗号、转义双引号及嵌入换行，不按物理行切割。
逐批调用现有转换器，比较转换结果和原记录的全部字段及顺序，保存 UTF-8 JSON 数组。
生成的 `manifest.json` 记录每批从 1 开始的原始数据记录范围（两端包含，不计表头）、条数、文件名、JSON 字节数及整次最大值。
成功后才生成清单；失败可能留下部分批文件，请使用新的输出目录重新运行。
仅表头输入生成零批次清单。原数据不去重，重复值记录仍按各自位置分配一次。

200 是初始记录数上限，不是字节上限，不能保证任意数据都符合未来 HTTP payload 限制。
字节数为当前保存的 UTF-8 JSON 数组实际大小，不含未来 HTTP 或 Lambda 包装。
未来接口确定后需按实际请求和响应字节数制定分批策略；本工具目前不发送网络请求。

## Transform Function URL 接口

Lambda Java 21 handler：`edu.uw.tcss562.tlq.TransformUrlHandler::handleRequest`。
部署包仍为 `target/client-sync-tlq.jar`；本地 Main 和 BatchMain 入口保持可用。
采用 Function URL payload v2.0 和默认 BUFFERED 响应，不使用响应流。

- 请求：POST，`Content-Type: text/csv`（可带 `charset=utf-8`），body 直接为 UTF-8 CSV，不包 JSON。
- 根据 `isBase64Encoded` 解码事件 body，校验 Base64 和 UTF-8；客户端正常发送 CSV，无需自行 Base64。
- 成功：200，`application/json; charset=utf-8`，HTTP body 是原有 75 字段字符串对象数组。
- 错误：JSON 对象 `{"error":"错误码","message":"说明"}`；不返回 CSV 内容。
- 400：错误事件、Base64、UTF-8 或 CSV；405：非 POST，带 `Allow: POST`；415：不支持的 Content-Type 或 charset。
- 413 / `REQUEST_TOO_LARGE`：解码后 CSV 超过 1,048,576 字节（1 MiB）。
- 413 / `RESPONSE_TOO_LARGE`：完整 UTF-8 响应包装（含 JSON body 二次转义）超过 5,242,880 字节（5 MiB）。不截断数组，客户端应减少批记录数。
- 500：未预期的内部错误。限制是当前应用的保守常量，分别见 `MAX_REQUEST_BYTES`、`MAX_RESPONSE_BYTES`，不是 AWS 配额。

Function URL 会把 handler 的响应包装转成实际 HTTP 响应，HTTP 客户端接收到的是 body 数组。
AWS 非流式同步调用请求、响应各最多 6 MiB；请求事件包装和 Base64 会增加体积，平台可能在进入 handler 前直接拒绝超限请求。
响应预算检查的是 Jackson 序列化的完整返回对象，低于平台上限并预留余量；最终仍需部署后验证运行时包装。
参考：[事件格式](https://docs.aws.amazon.com/lambda/latest/dg/urls-invocation.html)、[AWS 配额](https://docs.aws.amazon.com/lambda/latest/dg/gettingstarted-limits.html)。

已按用户授权使用 `tcss562-dev` 在 `us-west-2` 部署 Java 21、512 MiB、30 秒的 Transform，Function URL 使用 AWS_IAM / BUFFERED。
执行角色仅允许向指定日志组写入日志，日志组保留 7 天；没有部署 Load、Query 或数据库。
部署与验证结果见 [Transform 部署记录](deployment/TRANSFORM.md)。`mvn clean package` 同时运行本地接口、转换和分批测试。

## Load 本地实现

复用现有 75 字段 JSON 契约，按 run_id 参数化批量 INSERT；每批独立事务，保留重复行、不自动重试。
未确认的日期、金额和空值规则暂不转换，原字符串写入兼容文本表。
Handler：`edu.uw.tcss562.tlq.LoadUrlHandler::handleRequest`，仍使用同一构建 JAR，不影响 Main、BatchMain 和 Transform handler。
环境配置、暂定 HTTP 名称、事务错误语义及单元测试/真实 MySQL 验证边界见 [Load 说明](deployment/LOAD.md)。
用户已完成本地真实 MySQL 8.4.9 集成测试：200 条、重复行、run_id 隔离、整批回滚及完整 998/9998 条验证通过。
结果见 [进度记录](deployment/PROGRESS.md)。Aurora 验证与 Load 部署尚未完成。
Query 已实现 MySQL 端 GROUP BY/COUNT/SUM/AVG，按 run_id 隔离，严格校验文本金额后显式转换 DECIMAL，规则集中且暂定。
构建的 48 项单元测试通过；真实 Query MySQL 新报告 PASSED / COMPLETED，9 项检查及 30 个聚合/过滤对照通过；旧失败报告保留原样。
本地 MySQL Load/Query 已验证，Aurora 和云端 Load/Query 尚未验证。接口及命令见 [Query 说明](deployment/QUERY.md)，接续工作见 [交接文档](deployment/HANDOFF.md)。

## Bash 同步编排

`scripts/tlq-sync.sh` 按每轮独立 run_id 执行分批、逐批 Transform→Load、提交数核对及最终 Query。
无自动重试，失败轮也保存步骤指标与整轮摘要；凭证仅在 Python 辅助进程内存中用于签名。
Windows Git Bash 用法、本地 HTTP 测试及验证边界见 [客户端说明](deployment/CLIENT_SYNC.md)。
本地 HTTP 测试不代表云端完整 TLQ 已验证，当前不部署或调用 AWS。
