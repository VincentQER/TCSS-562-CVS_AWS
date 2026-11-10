# 本地 MySQL Load 集成测试

目标是用户已安装的 Oracle MySQL 8.4.9，127.0.0.1:3306。
用户已手动完成建库、建表、设密及最小授权，并确认项目用户可登录。
初始化 SQL 保留供审阅，不应再次执行。用户已运行真实 JDBC 测试入口，全部通过；助手只读取报告，未代为执行数据库写入。
不得把 JDBC 替身单元测试当作真实数据库验证；最终 Aurora 引擎版本仍待定，本地 8.4.9 不能替代 Aurora 验证。

## 隔离与范围

- 新数据库：tlq_load_it；新账户：'tlq_load_it'@'127.0.0.1'，只允许本机 TCP/TLS。
- 新表：payments_raw、payments_rollback，均为 InnoDB。
- 仅对这两张表授予 INSERT、SELECT；不授予 DELETE、UPDATE、CREATE、ALTER、DROP、GRANT OPTION 或全局权限。
- 所有表名限定为新测试库，不访问其他业务数据；只读的 MySQL 系统元数据用于身份、版本和存在性检查。
- 若发现同名库或任何同名用户，停止，不覆盖、删除或自动复用。
- SQL 不含密码、不含随机密码生成、不使用 IF NOT EXISTS；不修改服务器全局设置。
- DDL 和账户操作不是一个可整体回滚的事务；任何步骤失败立即停止，已经创建的对象保留原样供检查。

## 测试表结构（不决定最终业务结构）

payments_raw 有 77 列：自动递增 test_row_id、run_id，以及全部 75 个 JSON 同名业务字段。
test_row_id 仅为本地夹具的代理主键，不对 Record_ID、源行内容或 run_id 设置唯一约束。
run_id 暂用 VARCHAR(128) 并建普通索引，75 个业务列暂用 LONGTEXT NOT NULL。
utf8mb4 / utf8mb4_0900_bin、128 长度和代理主键仅是本地测试选择，不是双方最终契约。
空字符串原样保留；HTTP 仍只接受 75 个业务字段，不要求客户端提供 test_row_id。

payments_rollback 与原表结构相同，额外 CHECK 拒绝 changeType='__TLQ_IT_FORCE_FAIL__'。
该标记仅注入 SQL 失败，第二条触发时验证第一条也被回滚，不代表业务转换或日期金额校验规则。

## 手动执行顺序（Git Bash）

以下命令由用户自己在本机终端执行；本次没有执行。
当前目录应为项目根目录。先检查下面路径对应的 mysqladmin.exe 是否存在；若不存在，停止报告，不安装或替换工具。
所有 -p 均没有紧跟密码值；只在工具的隐藏提示中输入密码，不运行 set -x、tee 或终端录制。

1. root 隐藏输入密码，执行只读存在性检查：

```bash
"C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe" --no-defaults --no-login-paths --protocol=TCP --host=127.0.0.1 --port=3306 --ssl-mode=REQUIRED --user=root -p < deployment/mysql-local/00-preflight.sql
```

确认版本为真实 Oracle MySQL 8.4.9；目标库和用户查询均为空。若非空或有错误，停止。

2. 分别创建专用测试库、表及无数据权限的本机账户（每条命令会提示 root 密码）：

```bash
"C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe" --no-defaults --no-login-paths --protocol=TCP --host=127.0.0.1 --port=3306 --ssl-mode=REQUIRED --user=root -p < deployment/mysql-local/01-schema.sql
"C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe" --no-defaults --no-login-paths --protocol=TCP --host=127.0.0.1 --port=3306 --ssl-mode=REQUIRED --user=root -p < deployment/mysql-local/02-bootstrap-user.sql
```

每条确认成功后才执行下一条；不要添加 --force。数据库/账户操作不支持靠整段 ROLLBACK 撤销。
新账户此时没有初始密码、没有数据库数据权限；不要停留在此阶段，立即完成下一步。
如果服务器策略不允许这种初始化方式，停止报告，不降低全局密码策略或改用明文密码参数。

3. 为新账户交互设置密码；password 必须是最后一个参数，后面不提供任何密码：

```bash
"C:/Program Files/MySQL/MySQL Server 8.4/bin/mysqladmin.exe" --no-defaults --no-login-paths --protocol=TCP --host=127.0.0.1 --port=3306 --ssl-mode=REQUIRED --user=tlq_load_it --skip-password password
```

--skip-password 仅适用于刚创建的无密码账户，避免旧密码提示；随后工具会隐藏提示输入并确认新密码。
不要在命令或 SQL 文件里补上密码，不生成随机密码，不把密码粘贴到聊天。
mysqladmin 会发送密码管理语句；MySQL 标准密码语句日志会脱敏，但服务器若启用了绕过脱敏的 raw logging、
第三方审计或外部终端录制，不能保证不落日志。若存在这些配置，停止并先确认日志处理，不擅自改全局配置。

4. 密码设置成功后，才执行最小授权：

```bash
"C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe" --no-defaults --no-login-paths --protocol=TCP --host=127.0.0.1 --port=3306 --ssl-mode=REQUIRED --user=root -p < deployment/mysql-local/03-grants.sql
```

5. 用项目用户验证身份及权限（密码隐藏输入）：

```bash
"C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe" --no-defaults --no-login-paths --protocol=TCP --host=127.0.0.1 --port=3306 --ssl-mode=REQUIRED --user=tlq_load_it -p --database=tlq_load_it
```

在 mysql 提示符执行以下只读语句：

```sql
SELECT VERSION(), CURRENT_USER(), DATABASE();
SHOW GRANTS;
SHOW CREATE TABLE tlq_load_it.payments_raw;
SHOW CREATE TABLE tlq_load_it.payments_rollback;
SELECT COUNT(*) FROM tlq_load_it.payments_raw;
SELECT COUNT(*) FROM tlq_load_it.payments_rollback;
```

预期 CURRENT_USER() 为 tlq_load_it@127.0.0.1；初始两表均为零条。
SHOW GRANTS 可能同时显示 USAGE、账户 REQUIRE SSL；不应有额外数据库权限。
不需要 FLUSH PRIVILEGES，不修改已有账户或授权。
若使用其他 Host 的账户、同名对象或权限不符，停止报告，不自动放宽 Host 或授权。

## 真实 JDBC 测试入口（已实现，需用户隐藏输入密码后运行）

测试类：`src/test/java/edu/uw/tcss562/tlq/MysqlIntegrationMain.java`，仅位于测试源码，Maven 不会自动连接数据库执行它。
在项目根目录的 Git Bash 运行（先构建测试类及 JAR）：

```bash
mvn clean package
winpty java -cp 'target/test-classes;target/client-sync-tlq.jar' edu.uw.tcss562.tlq.MysqlIntegrationMain
```

winpty 为 Git Bash 的交互控制台适配器；不通过管道或重定向传入密码。
如 java 不在 PATH，可以将 java 替换为 `"C:/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot/bin/java.exe"`。
若没有 winpty，可在 Windows Terminal 的 PowerShell 中运行同一 Java 命令（去掉 winpty）；密码仍由 Java console 隐藏提示。
若提示 No secure console，停止，不回退到明文输入、环境密码、文件或命令参数。

只检查 CSV 输入且不连接数据库的命令：

```bash
java -cp 'target/test-classes;target/client-sync-tlq.jar' edu.uw.tcss562.tlq.MysqlIntegrationMain --check-inputs
```

优先读取 test-data/ 中的 1K_RECORDS.csv、10K_RECORDS.csv，否则读取项目根目录同名文件；运行前验证其记录数为 998、9998。
生成独立的随机 run_id；分批上限 200。真实 200 条、重复两行、两个隔离 run、CHECK 回滚和完整 1K/10K 均逐行逐字段比较。
完整数据分别加载 5 批、50 批；不删除既有数据，不自动重试。
在写入前将当前 run_id 保存到已忽略的 `test-output/mysql-integration-it-<UUID>.json`，之后逐项更新状态。
报告只包含测试状态、run_id、计数及数据库版本/TLS等非敏感元数据，不保存密码或完整 JDBC 异常。
本次已完成并输出 ALL REAL MYSQL TESTS PASSED，报告 session 为 it-159a3602-19b7-48b9-9c54-6c0f45d67514；详细核对见 [进度记录](../PROGRESS.md)。
失败或结果不确定时停止并保留数据和报告，不自动重新运行。
提交异常可能留下结果不确定的批次；不能把缺少 PASSED 或报告中的部分计数当成已回滚的证明。

本轮手动测试实现范围：用户要求的五类用例。下方历史计划中的空批次、手动重复同一请求及 HTTP 事件到真实 JDBC 测试尚未加入此次入口。

测试入口需交互读取项目用户密码，使用 System.console().readPassword() 或等效隐藏输入，仅在当前进程内存中交给 JDBC。
如当前终端无法隐藏输入，停止；不回退到命令参数、文件、日志或 Maven -D 密码。
不能让测试自动读取真实 DB_PASSWORD 环境变量或秘密文件。
当前生产连接工厂读取环境变量；测试入口使用交互取得的密码构造仅存在于内存中的配置映射，
通过 JdbcLoadService.ConnectionFactory 注入并复用相同连接属性及元数据检查，不读取实际密码环境变量，不改变生产 handler。

固定目标 127.0.0.1:3306 / tlq_load_it，只用项目用户写这两表；无 root 运行测试、无自动建表或清理。
每次验证生成新的 run_id，所有验证 SELECT 绑定 run_id。
使用真实 MySQL Connector/J 和 JdbcLoadService，不用 mock，也不将一个外层测试事务包住 Load 来伪造其提交边界。
使用独立观察连接确认提交/回滚结果。记录 VERSION、Connector/J 版本、引擎、SQL mode、字符集和排序规则，不记录凭证。
本机选择 TLS REQUIRED，不将其当作 Aurora VERIFY_IDENTITY/TLS 验证结果。

| 用例 | 操作 | 独立连接预期 |
| --- | --- | --- |
| 真实批量 INSERT | 完整样例的前 200 条，run A | 返回 committedRecords=200；run A 恰好 200 条，75 个字段逐条匹配 |
| 源重复行 | 两个完全相同的对象，同一个新 run B | 返回 2，数据库两行均保留，Record_ID 可相同 |
| 实验隔离 | 同一数据分别用 run C、run D 加载 | 各 run 的条数独立，过滤一个 run 不返回另一个 run 的数据 |
| 无幂等行为 | 在专门的新 run E 手动调用两次相同 Load | 两次各返回 N，累计 2N；这是受控测试，不是客户端自动重试 |
| 批次整体回滚 | payments_rollback：第一条正常，第二条仅将 changeType 设为故障标记 | Load 失败，返回无成功条数；该 run 在回滚表中为 0，前面成功实验不受影响 |
| 空批次 | 空数组 | 返回 0，不新增数据 |
| HTTP 到 JDBC | 通过真实 LoadUrlHandler 注入同一交互配置，验证普通及 Base64 JSON 事件 | 返回 HTTP 200 和已提交条数，内容在真实数据库可见 |

回滚用例必须使用合法的 75 字段 JSON，让错误发生在数据库执行批次中，不能用缺失字段冒充 SQL 回滚验证。
暂不通过断开 MySQL 服务、KILL 其他连接等方式注入故障；COMMIT 断网不确定性仍由单元测试覆盖，不宣称已真实验证。
没有自动重试。任何提交结果不确定立即停止整轮测试并报告，不自动清理或重发。
清理不属于当前步骤；需要时另行审核只针对测试库的清理 SQL。
COUNT 不去重；金额 SUM/AVG 不在本轮范围内，精度、空值及转换规则仍待确认。

## 参考

- https://dev.mysql.com/doc/refman/8.4/en/mysqladmin.html
- https://dev.mysql.com/doc/refman/8.4/en/password-logging.html
- https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html
