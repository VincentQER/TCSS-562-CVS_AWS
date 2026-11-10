# TCSS 562 客户端同步 TLQ 交接

## 当前结论

本地 Oracle MySQL 8.4.9 的 Load 和 Query 已真实验证；Aurora MySQL 与云端 Load/Query 尚未验证、未部署。
Transform 已在 us-west-2 完成 AWS_IAM / BUFFERED 的一次真实 200 条云端调用验证。
当前同步方案不使用 S3；Bash→本地 HTTP→实际三个 handler→真实 MySQL 的完整 1K 流程已通过，云端完整串联及正式性能/成本实验尚待完成。

## 本地验证证据

- 最近构建：50 项 Java 单元/回环 HTTP 测试通过。凭证只由用户在本机隐藏交互输入，报告不含凭证。
- Load：mysql-integration-it-159a3602-19b7-48b9-9c54-6c0f45d67514.json，7 项通过。
  真实 200 条、重复两行、run_id 隔离、CHECK 3819 整批零提交、完整 998/9998 条（5/50 批），独立连接逐字段核对。
- Query：query-mysql-it-5ae74120-0604-41b6-9c22-a1fa13f924f3.json，PASSED / COMPLETED，9 项通过。
  六个已有 run 各 5 次分组/过滤对照，共 30 次；另有空结果、派生金额边界和本地 HTTP 事件到真实 JDBC 检查。
- 两份成功报告均在 test-output/（已忽略）；已核对 Query 引用的 run_id 与成功 Load 报告一致。
- Query 首份 8c5c1e88-32fd-4a82-9b11-e5bc80ef54b1 报告保留 FAILED，诊断信息不足，不能倒推出其具体失败根因；不覆盖为通过。
- 本地 HTTP 事件检查不是云端 Function URL 验证；本地 MySQL 结果不是 Aurora 兼容性或云端性能结论。
- 完整本地链路：test-output/local-real-tlq-47f59b91-c388-4b49-8e4f-e8db1b9de4c3/verification.json 为 PASSED / COMPLETED。
  真实 Bash、HTTP 适配、实际 Transform/Load/Query handler 与 MySQL；5 批共 998 条、11 次 HTTP 全部 200，独立连接 75 字段一致。
  最终 count=998、amountCount=998、sum="14493.04"、avg="14.5221"；客户端整轮 5004.323 ms，Bash 进程 5700.8199 ms。
  仅为本地功能观测，不是云端性能结果；完整 10K 的此种端到端验证尚未运行。

## 已实现接口与规则

- Java 21，构建 mvn clean package；JAR 为 target/client-sync-tlq.jar。
- TransformUrlHandler、LoadUrlHandler、QueryUrlHandler 均在 edu.uw.tcss562.tlq 包。
- 保留原 75 字段字符串 JSON，源重复记录不去重，Record_ID 无唯一约束。
- Load 每次 INSERT，以 run_id 隔离；每批事务，提交确认后返回条数，不自动重试。提交结果不确定则停止实验。
- Query 必填 run_id，参数绑定、分组白名单；MySQL 执行 GROUP BY/COUNT/SUM/AVG，先校验文本金额再在合法 CASE 分支显式转换 DECIMAL。
- 金额规则集中在 QueryRules，版本 provisional-v1。空金额排除出 SUM/AVG，COUNT 包含所有匹配行，非法非空金额使整次金额查询失败。
- AVG 最终格式化只使用数据库已聚合的 SUM/有效数，避免中间舍入误差，不读取明细到 Java 聚合。
- 详见 [Load](LOAD.md)、[Query](QUERY.md)、[接口契约](INTERFACE_CONTRACT_DRAFT.md)、[进度](PROGRESS.md)。
- 两套 pipeline 已同意使用相同查询；可交给队友审阅的 [共享 SQL 与输入输出草案](SHARED_QUERY_CONTRACT.md) 已整理，具体金额/空值/精度/舍入规则仍待队友确认。
- Bash 入口 scripts/tlq-sync.sh，签名/指标辅助程序 scripts/tlq_client.py；详见 [Windows Git Bash 客户端说明](CLIENT_SYNC.md)。本地 HTTP 服务是测试替身，不代表云端 Load/Query 或 Aurora。
- [Bash→实际 handler→真实 MySQL 完整链路](LOCAL_REAL_CHAIN.md) 已由用户隐藏输入密码手动运行并通过，与前述内存 HTTP 替身测试区分。

## 仍需确认/完成

- 与队友最终对齐金额语法、空值、精度/舍入、日期、字符集/排序规则、正式表结构和 HTTP 命名；当前暂定规则不视为最终约定。
- Aurora MySQL 区域 us-east-2，实例规格及引擎版本待确认；本地 8.4.9 系列与最终 Aurora 需重新核对。
- 现有 Transform 在 us-west-2，Load/Query 部署区域、网络可达性、TLS、数据库凭证提供方式和最小权限尚需单独设计和授权。
- 后续云端操作只能使用显式 profile tcss562-dev、region，并先核实非 root；当前部署权限只覆盖 Transform，不能默认为已有 Load/Query/RDS 权限。
- 尚未验证真实 Aurora、云端 Load/Query HTTP、断网 COMMIT 不确定性、云端完整同步流水线以及重复实验性能/成本；本地完整 1K 流程已通过。
- 不自动删除测试数据、重跑初始化、创建云资源、扩大 IAM 权限、提交或推送；下一步工作需按用户指令推进。

本次交接更新只读取既有报告、修改文档，没有重新查询或修改数据库，也未调用 AWS。

## Aurora MySQL 部署前待确认配置

- 区域已计划 us-east-2；确认具体 Aurora MySQL 引擎版本及其 MySQL 兼容系列，与本地 8.4.9 的差异。
- 计算方式、实例规格/或 Serverless 容量范围，writer/reader 数量、可用区和高可用需求。
- VPC、数据库子网组、安全组及 Lambda 到数据库的端口访问；Load/Query 区域和 VPC 配置；现有 Transform 在 us-west-2。
- 正式数据库/表名、文本金额或额外 DECIMAL 列、DDL、字符集/排序规则、索引、共享查询及暂定金额规则最终确认。
- 数据库管理员与项目最小权限用户，认证方式、凭证存放/轮换、TLS 证书信任，Load 写端点与 Query 读一致性要求。
- Load/Query Lambda 执行角色、数据库与凭证访问权限、Function URL AWS_IAM 调用权限、超时/内存和连接数上限；当前开发权限只覆盖 Transform。
- 备份保留、删除保护/最终快照、维护与升级策略、监控日志、费用预算及实验后保留/清理方案。

以上仅为待确认清单，不代表资源已存在或已授权创建；云端实例可用性和费用需另行只读核对。
