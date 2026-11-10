# Query 本地实现（未部署，规则暂定）

## 当前状态

实现 JdbcQueryService、QueryUrlHandler、QueryRequest 与 QueryRules。
原 75 字段 JSON、现有表和已有数据保持不变，不创建额外金额列、不回填、不部署、不调用 AWS。
最新干净构建通过，共 48 项单元测试通过（Query 14 项、诊断 3 项，原有 31 项）。
真实 Query MySQL 已由用户手动运行并通过，新报告 query-mysql-it-5ae74120-0604-41b6-9c22-a1fa13f924f3.json 为 PASSED / COMPLETED，9 项检查通过。
首次报告 query-mysql-it-8c5c1e88-32fd-4a82-9b11-e5bc80ef54b1.json 继续保留 FAILED，不覆盖历史；单元测试与真实 SQL 验证分别记录。

## 集中的暂定规则 provisional-v1

以下是本次获准采用的暂定实现规则，之后仍需与队友对齐，主要定义在 QueryRules.java：

- 必填 X-TLQ-Run-Id，不 trim，不允许空白实验编号。
- 金额来源 totalAmountOfPaymentUsdollars 文本列；不改变金额原文。
- 金额允许负号，不允许正号、指数、逗号、货币符号、空白、尾随字符或非 ASCII 数字。
- 1 到 18 位整数，可有 1 到 2 位小数，前导零允许但计入 18 位上限，不允许 .1 或 1.。
- 校验通过后显式 CAST 为 DECIMAL(20,2)；不先 CAST 再判断是否合法，不截断或逐笔舍入。
- SQL NULL 和长度为 0 的字符串是缺失金额；纯空格非法。用 OCTET_LENGTH 判空，避免某些排序规则将空格等同空字符串。
- COUNT 包含所有匹配记录，重复行也计入；SUM/AVG 不计缺失金额，amountCount 表示有效金额分母。
- 请求 SUM/AVG 且任何匹配金额非法，整个请求返回 422 / INVALID_AMOUNT，无部分分组结果。
- 仅请求 count 时不校验金额，不因金额内容拒绝计数。
- SUM 保持十进制精确累计，返回两位小数字符串，不把累计结果再 CAST 回单笔 DECIMAL(20,2)。
- MySQL 实际执行 GROUP BY、COUNT、SUM、AVG，Java 不读取明细聚合。
- AVG 最终显示四位小数、HALF_UP；Java 仅对数据库已聚合的 SUM/amountCount 做最终除法格式化，避免 MySQL AVG 中间精度导致双重舍入。
  SQL AVG 同时执行并读取。这个格式化路径、正负号、位数及其他暂定规则都需要双方最终对齐，不是额外 Java 明细聚合。
- 无有效金额时 sum、avg 为 JSON null；无分组且无匹配行返回一条 count=0、amountCount=0、sum=null、avg=null。
  有分组且无匹配行返回 results=[]。
- 州/年份不规范化，按文本绑定比较；分组和比较沿用目标表字符集/排序规则，本地夹具为 utf8mb4_0900_bin，正式库规则仍待确认。

金额分类、合法分支 CASE CAST、分组和非法计数位于同一 SQL；不依赖 WHERE/AND 顺序或 CTE 必然物化来保证转换安全。
正则加额外字符排除检查，拒绝可能被美元锚点放过的末尾换行。
数据库警告、SQL 错误或无法确认结果一律失败，不依赖 SQL mode 自动替我们拒绝非法金额。

## HTTP 接口（名称仍为暂定）

Handler：`edu.uw.tcss562.tlq.QueryUrlHandler::handleRequest`，Function URL v2.0。
POST，Content-Type application/json，可带 charset=utf-8；支持 isBase64Encoded 和严格 UTF-8。
环境配置复用 Load 的 DB_HOST、DB_PORT、DB_NAME、DB_TABLE、DB_USER、DB_PASSWORD、DB_SSL_MODE，不硬编码或输出密码。
连接工厂仍检查现有 75 字段文本 InnoDB 表，不修改表或连接权限。

```json
{
  "filters": {"state": "WA", "year": "2018"},
  "groupBy": ["state", "year"],
  "metrics": ["count", "sum", "avg"]
}
```

filters 可省略，州/年份值必须为字符串；空字符串可用于匹配源字段空值，不接受年份数字隐式转换。
groupBy 可省略或为空，白名单仅 state、year，禁止重复项。
metrics 可省略（默认三项），显式提供时必须非空且无重复，只接受 count、sum、avg。
未知键、客户端 SQL、表达式和自定义排序全部拒绝。run_id 必须来自请求头，不能用 body 覆盖。

成功响应：200，`{"runId":"...","rulesVersion":"provisional-v1","results":[...]}`。
每组包括选定分组键及选定指标；请求金额指标时额外提供 amountCount。示例：

```json
{"state":"WA","count":3,"amountCount":2,"sum":"30.00","avg":"15.0000"}
```

400：请求/编码/白名单错误；405：非 POST；415：媒体类型或字符集不支持；422：非法金额；500：数据库/内部错误（脱敏）。
应用限制：请求解码后最多 64 KiB，聚合分组最多 10,000 个，完整响应包装最多 5 MiB；超限返回 413，不截断统计。
这些限制及默认接口字段都是可调整的应用选择，不代表 AWS 配额或最终队友契约。

## 真实 MySQL 只读验证（用户已手动运行通过）

沿用已通过的 Load 报告和现存 run_id，不创建数据库/表/用户，不 INSERT、UPDATE 或 DELETE。
测试读取最新 PASSED 的 test-output/mysql-integration-it-*.json，使用原 CSV 在测试代码中独立计算参考值。
Java 参考计算仅为测试预期，生产 Query 仍是数据库端聚合。

在项目根目录 Git Bash 执行（本次已构建，无需再次构建）：

```bash
winpty java -cp 'target/test-classes;target/client-sync-tlq.jar' edu.uw.tcss562.tlq.MysqlQueryIntegrationMain
```

如 java 不在 PATH，替换为 `"C:/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot/bin/java.exe"`。
只在隐藏提示输入 tlq_load_it 密码，不通过参数、环境变量、管道或文件提供密码。
若无安全 console，直接退出；无 root、default 或其他账户回退。
目标固定为 MySQL 8.4.9 / 127.0.0.1:3306 / tlq_load_it，TLS REQUIRED，使用已有项目用户和 SELECT。

覆盖已加载六个 run 的不分组、州分组、年分组、双字段分组及州/年过滤，共 30 个 SQL 聚合对照；
另检查空 run、只读派生数据中的非法金额/边界 CASE CAST，以及本地 HTTP 事件到真实 JDBC（不启动 HTTP 服务器）。
派生边界数据只作为 SELECT UNION ALL 的绑定参数，不插入数据库；复用生产金额分类及转换表达式。
非空非法金额触发整个 HTTP 422 的路径目前由单元测试覆盖，不能称为已在真实表脏数据上验证。
成功输出 ALL READ-ONLY MYSQL QUERY TESTS PASSED，报告写入已忽略的 test-output/query-mysql-it-*.json。
报告不保存密码、原始 JDBC 错误、业务明细或签名信息。失败时停止，数据不变。
真实 Aurora、云端 HTTP、性能与成本比较仍未验证。

## 安全诊断

首份失败报告只有通用 QUERY_INTEGRATION_FAILED 和空 checks，无法区分认证、控制台或数据库预检问题。
新版按阶段记录 step，失败时记录 diagnostic.category、exceptionType，以及可用的 SQLState/数字数据库错误码。
不输出原始异常消息、密码、配置对象、SQL 绑定值或堆栈；HTTP 响应仍保持脱敏。

| 新诊断 | 操作 |
| --- | --- |
| NO_SECURE_CONSOLE / SECURE_CONSOLE | 用 Git Bash winpty 命令或 Windows Terminal 原生 PowerShell 运行 Java，不能通过管道输入密码 |
| PASSWORD_CANCELLED_OR_EMPTY | 重新手动启动，在隐藏提示输入项目用户密码，不使用 root |
| AUTHENTICATION_FAILED，1045 或 SQLState 28xxx | 用 mysql.exe --no-defaults --no-login-paths --protocol=TCP --host=127.0.0.1 --port=3306 --ssl-mode=REQUIRED --user=tlq_load_it -p --database=tlq_load_it 手动核对登录；不要自动改密码 |
| AUTHORIZATION_FAILED，1044/1142/1227 | 只读 SHOW GRANTS，报告缺少的操作，不自动扩大权限 |
| CONNECTION_OR_TLS_FAILED，SQLState 08xxx | 核对 MySQL 服务、本机 3306 及 TLS；不自动启动服务或关闭 TLS |
| DATABASE_IDENTITY_MISMATCH / DATABASE_VERSION_MISMATCH | 停止并核对目标账户、数据库和版本，不跳过保护检查 |
| LOCAL_FILE_ERROR / NO_PASSED_LOAD_REPORT | 从项目根目录运行，核对 CSV 与已通过 Load 报告的位置 |
| DATABASE_ERROR，EXECUTE_AGGREGATE | 根据新版 SQLState、数字错误码及 queryIndex 定位；不输出原始驱动错误消息 |

新版报告已整体 PASSED，可将本地真实 Query 记为通过；不能据此将首次失败报告改为 PASSED，也不能推断旧报告的具体失败根因。
本次报告核对没有更改金额规则、数据库数据或任何原始报告。Aurora、云端 Load/Query、性能与成本对比仍未验证。
