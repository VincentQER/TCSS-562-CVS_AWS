# Transform 部署及验证记录

部署身份：`arn:aws:iam::803964124311:user/tlq-dev`；全部 AWS CLI 调用显式使用 `--profile tcss562-dev --region us-west-2`，未回退 root/default。

| 项目 | 实际配置 |
| --- | --- |
| 函数 | tcss562-sync-transform |
| 运行时、架构 | Java 21、x86_64 |
| 内存、超时 | 512 MiB、30 秒 |
| Handler | edu.uw.tcss562.tlq.TransformUrlHandler::handleRequest |
| 执行角色 | tcss562-transform-exec（只写指定日志组） |
| 日志组 | /aws/lambda/tcss562-sync-transform，保留 7 天 |
| URL 认证、响应 | AWS_IAM、BUFFERED |
| JAR 大小 | 3,247,731 字节 |
| JAR / 云端 CodeSha256 | SDNwgLqTmvE6ZFikj1ot59w7tLo0Rb6NshnwPc0/BLY= |

Function URL：https://bmspraci2ildwvjxpqq4rku42y0xraqo.lambda-url.us-west-2.on.aws/

最新源码执行 `mvn clean package`，15 项测试全部通过；云端代码哈希与 JAR 一致。
真实输入为完整 1K 数据中最前面的 200 条，批 CSV 为 103,734 字节；签名 POST 返回 200。
返回 JSON 为 619,834 字节，200 个对象，每个 75 个字符串字段，逐字段及顺序均与本地 JAR 转换结果和原始数据一致。
同 URL 的未认证 POST 返回 403。
签名使用开发 profile 的临时凭证，仅在验证进程内存中使用，未输出或写入文件。

本次请求 ID：f238a556-c44c-4da8-a08b-2bcb89426259。
CloudWatch REPORT：Duration 829.23 ms；Init Duration 847.84 ms；Billed Duration 1678 ms；Max Memory Used 117 MB。
客户端端到端 2221.99 ms。以上为单次冷启动观测，不代表稳定性能或平均值。
忽略目录 `test-output/` 保存部署元数据、验证摘要及返回 JSON，不包含凭证或签名请求头。

未修改或扩大 IAM 权限，未部署 Load、Query、数据库，未提交或推送代码。
