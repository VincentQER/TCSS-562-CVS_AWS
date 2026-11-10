# Load 本地实现说明（未部署）

## 尚未确认的业务规则

- 空字符串、空格、JSON null、缺失字段及 trim 的处理。
- 日期格式、严格日历校验后的非法值处理；未来需兼容样例实际月/日位数。
- 金额总精度、小数位、负数、分隔符、非法值及超限值处理。
- 字段长度、最终字符集/排序规则、年份及支付次数的数据库类型。
- Query 的 SUM/AVG 精度、舍入及空值行为。
- 请求头及响应字段最终命名、run_id 长度与生命周期、表主键和源位置追踪。

本实现不擅自决定上述转换规则：75 个值原样 setString，包括空字符串和看似非法的日期或金额。
不将空字符串转 SQL NULL，不 trim，不解析、规范化或舍入日期/金额。
JSON 结构校验沿用现有 Transform 输出：恰好 75 个已知字段且值都是字符串；缺失、额外、null、非字符串、重复 JSON 键或尾随 JSON 均拒绝。
这只是结构校验，不代表业务日期或金额已通过校验。

## HTTP 接口（名称暂定，待队友确认）

- Handler：`edu.uw.tcss562.tlq.LoadUrlHandler::handleRequest`，支持 Function URL payload v2.0。
- POST，Content-Type: application/json（可带 charset=utf-8）。
- `X-TLQ-Run-Id` 必填且非空白，原样绑定到 run_id；未强制限定其业务格式或长度。
- body 为现有 75 字段 JSON 数组；支持 isBase64Encoded，并严格校验解码后的 UTF-8。
- 解码后请求体应用上限 1 MiB（不是 AWS 配额），超过返回 413；200 条不是固定安全字节数。
- 成功：200，`{"committedRecords":200}`，只在 COMMIT 确认后返回。空数组返回 0，不连接数据库（默认 handler 仍会校验环境配置）。
- 400：事件、JSON、字段结构、编码或 run_id 错误；405：错误方法；415：错误类型/字符集。
- 500 / LOAD_NOT_COMMITTED：未写入或事务回滚已确认，不返回成功条数。
- 500 / COMMIT_OUTCOME_UNKNOWN：COMMIT 报错或回滚无法确认；即使随后 rollback 返回成功，也不假定此前 COMMIT 未发生。
- 500 / LOAD_UNAVAILABLE：配置或其他内部错误；不暴露原始驱动异常、连接信息、字段值或密码。
- 错误包含 `retryable:false`。客户端不能自动重试 Load；提交结果不确定时停止该轮实验，不继续后续批次或正式 Query 实验。

## JDBC 与表的前置要求

使用 MySQL Connector/J 8.4.0；Aurora 引擎版本仍待定，驱动与最终引擎兼容性尚未做真实验证。
服务对指定表执行单条参数化批量 INSERT，列顺序为 run_id 加 PaymentSchema.JSON_FIELDS 中的 75 个 JSON 驼峰字段。
表名仅来自环境配置，并限制为合法单个 SQL 标识符；所有数据值及 run_id 均使用绑定参数。

本阶段暂要求已存在的 InnoDB 文本暂存表：run_id 和 75 个业务列为文本类型，以免数据库擅自转换日期或金额。
连接工厂只读检查这些列的 JDBC 类型及 InnoDB 引擎，不创建或修改表。
表应允许重复业务记录，不得对 Record_ID 或业务字段设置去重唯一约束；额外列若存在必须有默认值或可自动生成。
表主键、文本长度、最终排序规则和索引尚未决定，没有确定正式类型化表结构。
独立本地测试用 DDL 和手动执行步骤见 [MySQL 本地集成测试](mysql-local/README.md)，仅为测试夹具；用户已手动建库建表并通过 Load/Query 本地验证，不应重复执行初始化 SQL。
数据库严格模式、字段长度、约束和触发器仍需真实 MySQL 验证；客户端列类型检查不证明服务端没有额外转换或副作用。
数据库发出 SQLWarning 时整批回滚，避免静默接受截断或转换警告。

每个非空请求新建一次连接，关闭自动提交，executeBatch 后提交；写入异常整批回滚，不重试。
SUCCESS_NO_INFO 作为成功处理；失败计数或结果条数不符则回滚。计数只在提交确认后返回。
提交成功后连接关闭异常不会把已确认成功改报失败，避免诱发重复加载。
不实现 load_batches、内容哈希、UPSERT 或重试幂等，重复请求可能再次插入。

## 环境配置（不含任何真实值）

必填：DB_HOST、DB_NAME、DB_TABLE、DB_USER、DB_PASSWORD。
可选：DB_PORT（默认 3306）、DB_SSL_MODE（默认 VERIFY_IDENTITY）。
SSL mode 可为 VERIFY_IDENTITY、VERIFY_CA、REQUIRED 或 DISABLED；DISABLED 仅用于明确选择的本地验证。
连接使用单主机地址；autoReconnect=false、autoReconnectForPools=false、continueBatchOnError=false，不自动重连或重试。
连接超时 5 秒、socket 超时 20 秒；未来 Lambda 超时和客户端超时仍需统筹配置。
UTF-8 为传输编码，不代表已经决定数据库最终字符集或排序规则。
密码通过 JDBC Properties 在内存中传递，不放进 URL、SQL、日志或 HTTP 错误，不提供包含密码的 toString。
不读取本机凭证文件或打印环境变量；真实本地测试由用户隐藏输入密码后运行，结果见下方。

## 验证范围

单元测试使用 JDBC 接口替身验证参数绑定、重复行保留、事务提交/回滚、提交结果不确定、连接关闭异常和无重试行为。
HTTP 测试注入 Loader 替身，检查普通/Base64 请求、结构校验、状态码和错误脱敏；不经过真实网络。
连接配置测试使用虚拟配置，不连接 MySQL。现有 Transform 与分批测试同时保留。
真实本地 MySQL 验证：用户已运行并通过，报告 session 为 it-159a3602-19b7-48b9-9c54-6c0f45d67514。
MySQL 8.4.9 / Connector-J 8.4.0：200 条真实批次、重复两行、run_id 隔离、CHECK 故障整体回滚及完整 998/9998 条数据逐字段核对通过。
各项均使用新的 run_id 和独立观察连接；报告已核对。详细环境及结果见 [进度记录](PROGRESS.md)。
尚未验证真实 Aurora、云端 Load HTTP、故障断网的 COMMIT 不确定状态或性能；本地测试不替代这些验证。

构建命令：`mvn clean package`。本次仅本地实现与测试，不创建 AWS 资源、不部署。

本次构建结果：成功；共 31 项单元测试通过（Load/JDBC/连接配置 16 项，原有转换、分批及 Transform 15 项）。
真实本地 MySQL 报告包含 7 个通过用例（隔离分为 A/B 两个）；Aurora 验证仍未执行，未混淆单元与集成测试。
