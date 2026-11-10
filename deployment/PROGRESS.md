# 项目进度

## 已完成

- Java 21 本地 CSV 转换、按逻辑记录分批和 Transform Function URL handler。
- Transform 已在 us-west-2 部署并验证 AWS_IAM / BUFFERED，真实 200 条签名调用通过。
- Load JDBC 批量 INSERT 和 HTTP handler 已本地实现；保留重复行，run_id 隔离，每批事务，无自动重试。
- Query JDBC 与 HTTP handler 已本地实现，MySQL 端聚合、文本金额严格校验及显式 DECIMAL 转换；真实本地 MySQL 只读验证已通过。
- Bash 同步客户端 scripts/tlq-sync.sh 已实现；本地回环 HTTP 自动测试 8 项通过，覆盖 13 轮场景（2 个成功轮、11 个预期失败轮），说明见 [客户端文档](CLIENT_SYNC.md)。
- 最近一次构建的 50 项 Java 单元/回环 HTTP 测试全部通过（另含 2 项实际 Transform handler 适配测试）；它们与真实 JDBC/完整链路测试分开记录。

## 本地真实 MySQL 验证已通过

用户在本机隐藏输入密码并运行 MysqlIntegrationMain；2026-11-09 读取并核对其生成报告，本次没有重新连接数据库或插入数据。
报告：test-output/mysql-integration-it-159a3602-19b7-48b9-9c54-6c0f45d67514.json（目录已忽略，不提交原始报告）。
整体状态 PASSED，7 个用例均 PASSED，7 个 run_id 各不相同，全部标记独立连接核对通过。

| 用例 | 已提交/核对条数 | 结果 |
| --- | --- | --- |
| 真实 200 条批次 | 200 | 75 字段逐条一致 |
| 两条完全重复记录 | 2 | 两行均保留 |
| 实验隔离 A / B | 2 / 3 | 各 run 单独查询，内容一致 |
| 第二条触发 CHECK | 0 | 错误码 3819，独立连接确认整批零提交 |
| 完整 1K | 998，5 批 | 已提交数及全部字段一致 |
| 完整 10K | 9998，50 批 | 已提交数及全部字段一致 |

环境：Oracle MySQL 8.4.9，127.0.0.1:3306/tlq_load_it；账户 tlq_load_it@127.0.0.1。
驱动：MySQL Connector/J 8.4.0；数据库 utf8mb4 / utf8mb4_0900_bin；TLS_AES_256_GCM_SHA384。
代码中的 verifyRows 在 fresh observer connection 上，按 test_row_id 排序读取并比较全部 75 个字段及行数。
报告不包含实际字段值；以上内容一致结论来自已通过测试的实现和报告状态，不是本次重新查询数据库。

## 本地真实 Query MySQL 验证已通过

用户手动运行新版入口，报告 test-output/query-mysql-it-5ae74120-0604-41b6-9c22-a1fa13f924f3.json。
整体 PASSED / COMPLETED，9 项检查全部通过；6 个已加载 run_id 与原 PASSED Load 报告逐项对应。
每个 run 有 5 个查询对照，共 30 个：不分组、州分组、年份分组、双字段分组和州/年份过滤。
另通过空 run、只读派生金额边界 CASE/CAST、本地 HTTP 事件到真实 JDBC 三项检查。
环境：MySQL 8.4.9，规则 provisional-v1，div_precision_increment=4。
金额聚合与源 CSV 独立参考结果一致，包含源重复行；边界测试使用 SELECT 派生值，无数据库数据写入。
HTTP 检查是本地事件调用 handler，不是已部署的网络 HTTP 或云端 Lambda 调用。
本次助手只读取报告、核验状态与源 Load 报告关联，不重新连接数据库。原始报告保留在 Git 忽略目录。

## 验证边界与下一步

- 真实 MySQL JDBC 逻辑已验证，不代表 Aurora MySQL、云端 Load HTTP、故障断网提交状态或性能对比已验证。
- Aurora MySQL 计划区域 us-east-2，实例规格和引擎版本仍待确认；本地 8.4.9 版本差异需最终核对。
- Load/Query 均未部署；本地真实 MySQL 验证已通过，Aurora 和云端 Load/Query 尚未验证，说明见 [Query 本地实现](QUERY.md)。
- Query 草案已调整为优先 MySQL GROUP BY/COUNT/SUM/AVG，比较额外类型化金额列与查询时校验 CAST；不默认把明细搬到 Java 聚合。金额规则及方案选择仍待双方确认。
- 日期/金额转换、空值、聚合精度、正式表结构及最终接口名称仍有待队友最终确认项；Query 按集中定义的 provisional-v1 暂定规则实现。
- 本次仅更新文档，未创建 AWS 资源、修改数据库、提交或推送。

## Bash 编排本地 HTTP 验证

本轮新增 Bash/Python 客户端、测试和文档；没有改 Java 业务实现或调用 AWS/真实数据库。
使用 401 条含中文、字段内换行、引号和重复行的 CSV，真实执行既有 Java 分批/转换。
默认 200/200/1，按 Transform→Load 逐批发送，最后 Query；自定义 500 条批大小也通过。
核对 Transform JSON 原字节转发、完整内容、Load 提交数、run_id 和 Query 无过滤计数。
预期失败覆盖 Transform HTTP/数量/内容、Load HTTP/数量/提交后断连/重定向、Query HTTP/run_id/总数，以及外部无认证地址拒绝。
所有失败轮保持 FAILED，无 Load 自动重试，不在失败后继续 Query；成功轮提交确认数为 401。
测试还验证显式 profile/region、root 拒绝和内存签名，使用虚拟凭证与替身命令，未调用真实 AWS CLI。
证据：test-output/local-http-client-validation/validation-summary.json 及各 run 的 summary.json、metrics.jsonl、响应文件（目录已忽略）。
Windows CRLF 分批清单问题已修复为 LF；Bash 语法检查及 git diff --check 通过。
这些是本地客户端 HTTP 编排验证，不代表云端三接口、Aurora、完整 IAM 流程或性能成本实验已通过。

## Bash→实际 handler→真实 MySQL 完整本地链路：通过

新增测试专用 LocalHandlerHttpAdapter 与 LocalRealTlqMain，不改生产业务规则。
由用户隐藏输入项目测试用户密码后，一条命令启动真实 handler 回环 HTTP 服务并调用 Bash，仅加载新的 run_id。
用户已手动运行完整 1K：998 条、5 批；最新报告为 test-output/local-real-tlq-47f59b91-c388-4b49-8e4f-e8db1b9de4c3/verification.json，PASSED / COMPLETED。
本轮 run_id：run-20261110T024300Z-c3ef06ca5cd34cdb98d45f1953c99feb。
已交叉核对 clientSummary：五批 Transform→Load 后才 Query，11 次 HTTP 全部 200，确认提交 998 条；独立连接确认 998 条、全部 75 字段一致。
最终 count=998、amountCount=998、sum="14493.04"、avg="14.5221"，与源数据参考值一致。
客户端整轮 5004.323 ms；父进程观测 Bash 启动至退出 5700.8199 ms；每次 handler 与 HTTP 耗时保存在报告及关联 summary.json/metrics.jsonl。
这是真实 Bash→HTTP→实际 handler→真实 MySQL 的本地完整流程通过，不是替身数据库响应；完整 10K 的此种端到端流程未在本轮运行。
计时仅为本地功能验证数据，不代表云端性能。云端完整流程、Aurora 和云端 Load/Query 仍未验证。
本次助手只读取报告并更新文档，没有重新运行或写入数据库，也未调用 AWS、创建资源、提交或推送。

## Query 首次真实测试失败与诊断修复

失败报告：test-output/query-mysql-it-8c5c1e88-32fd-4a82-9b11-e5bc80ef54b1.json。
原报告保持不变：FAILED / QUERY_INTEGRATION_FAILED，checks 为空，未记录 serverVersion 等预检成功元数据。
据此只能定位在预检完成之前（输入/控制台/密码/连接/身份/版本检查范围），不能认定为认证错误或 SQL 聚合错误。
助手只读复核：成功的 Load 报告仍存在；两份 CSV 输入条数仍为 998、9998，未连接数据库。
已确认的代码问题是诊断被通用错误吞掉，导致原报告无法说明根因。
已修复测试入口步骤追踪及安全错误分类；仅记录固定类别、阶段、SQLState 和数值错误码，不记录密码、原始驱动消息或堆栈。
此段保留首次失败历史，不将旧报告改为通过。新版真实 Query 验证现已通过，证据为上方独立的新 PASSED 报告；旧报告的具体根因不能从其通用错误倒推。
