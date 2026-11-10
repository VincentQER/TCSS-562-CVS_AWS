# 本地完整真实 TLQ 链路验证入口（待用户运行）

目标：Bash → 本地回环 HTTP → 实际 Transform/Load/Query handler → 真实 MySQL。
本次已构建入口并通过 50 项 Java 单元/回环 HTTP 测试，但未取得密码、未自动连接 MySQL，也未将本项记为通过。
先前的替身 HTTP 测试、本地分离的 Load/Query 集成报告均保留，不冒充本次完整真实链路结果。

## 手动运行（项目根目录，Git Bash）

本次已完成 mvn clean package。确认 Python 能在 Git Bash 中运行；必要时选择现有程序路径：

```bash
cd 'C:/Users/Vince/projects/TCSS-562-CVS_AWS'
export TLQ_PYTHON='C:/Python314/python.exe'
winpty 'C:/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot/bin/java.exe' \
  --add-modules jdk.httpserver \
  -cp 'target/test-classes;target/client-sync-tlq.jar' \
  edu.uw.tcss562.tlq.LocalRealTlqMain
```

只在 Java console 的隐藏提示中输入 tlq_load_it 的数据库密码，不提供 root 密码。
无安全 console、身份不符或 MySQL 版本不符立即失败，不接受通过参数、环境变量、文件或管道传入密码。
密码仅保存在父 Java 进程的 JDBC 配置内存中，不传给 Bash/Python 子进程或 HTTP 客户端。
如需选择 Git Bash 路径，可设置 TLQ_BASH；默认 Windows 为 C:/Program Files/Git/usr/bin/bash.exe。

## 实际执行内容

1. 读取完整 1K_RECORDS.csv（优先 test-data/，否则项目根目录），核对 998 条，并从源数据独立计算预期金额聚合。
2. 使用项目用户核对 MySQL 8.4.9 / 127.0.0.1:3306 / tlq_load_it，复用现有 payments_raw 文本 InnoDB 表。
3. 启动仅绑定 127.0.0.1 的临时 Java HTTP 服务，不注册系统服务，不模拟数据库操作。
4. /transform 直接调用实际 TransformUrlHandler；/load 直接调用实际 LoadUrlHandler 和 JdbcLoadService；
   /query 直接调用实际 QueryUrlHandler 和 JdbcQueryService。所有转换/校验/事务/SQL 聚合均复用现有代码。
5. 父进程调用 scripts/tlq-sync.sh --auth none，默认 200 条，预期五批 200/200/200/200/198。
6. Bash 生成独立 run_id；服务在第一次请求时只读确认该 run_id 尚无记录，并只允许本轮 run_id，避免写入已有实验。
7. 每批依次真实 HTTP Transform→Load，核对原始 JSON 字节转发及提交条数，成功五批后才发送最终无过滤 COUNT/SUM/AVG。
8. 核对 Bash 摘要已确认提交 998 条、5 批、11 次 HTTP 顺序与状态；核对最终 Query 的 count、amountCount、sum、avg 与源参考一致。
9. 再开独立 JDBC 观察连接，WHERE run_id=? 按 test_row_id 读取，核对 998 条全部 75 个字段，无遗漏、额外行或内容变化。
10. Bash 结束后关闭临时 HTTP 服务，保留本轮新插入数据和全部报告，不删除或覆盖任何既有实验数据。

如果失败，立即停止、不自动重试 Load，不自动删除部分提交；报告保留可用的 run_id、Bash 摘要和适配层状态。
没有新建/修改数据库、表、用户、权限或 AWS 资源；唯一数据库写入是对全新 run_id 执行正常 Load INSERT。

## 输出与计时

新目录：test-output/local-real-tlq-<UUID>/（已忽略）。

- verification.json：全链路 PASSED/FAILED、失败步骤/脱敏诊断、预期与实际聚合、独立 75 字段核对结果。
- runs/<run_id>/summary.json、metrics.jsonl：既有 Bash 每步 HTTP 状态、数量、耗时和整轮耗时。
- runs/<run_id>/responses/：真实三个 handler 的响应。
- verification.json 的 adapterSteps：服务端每个实际 handler 的 handlerElapsedMs 与适配层 adapterElapsedMs。
- clientTotalElapsedMs：Bash 轮次计时；bashProcessElapsedMs 包含父进程启动 Bash 及 Bash 自身启动/退出开销。

handlerElapsedMs 包含真实 JDBC 连接/校验、INSERT/事务或 SQL 聚合，不是纯 SQL 执行耗时。
这些全部为本机功能验证观测，包含进程启动、冷 JVM/JIT、客户端校验和本机资源竞争，不能当作云端 Lambda/Aurora 性能或成本结果。
报告 scope 固定为 LOCAL_FUNCTIONAL_VALIDATION_ONLY_NOT_CLOUD_PERFORMANCE。
不写密码、授权头或原始 JDBC 错误；报告中的聚合数值属于测试业务结果，不是凭证。

只有输出以下信息且 verification.json 为 PASSED / COMPLETED 才能将本项标为通过：

```text
ALL LOCAL REAL TLQ CHAIN TESTS PASSED; LOCAL FUNCTIONAL DATA, NOT CLOUD PERFORMANCE
```

请提供最终 verification.json 路径以便核对实测结果；在读取真实报告前不填写或推测耗时。
真实 Aurora、云端 Load/Query、AWS_IAM 完整三接口链路及正式性能对比仍未验证。
