# tlq-dev IAM 初始化待审批方案

状态：用户已批准并完成 IAM 初始化，AWS 只读核验通过；未部署 Lambda。
账户：803964124311
批准区域：us-west-2（变更区域必须同步修改所有区域 ARN）
用户：tlq-dev
函数：tcss562-sync-transform
执行角色：tcss562-transform-exec
日志组：/aws/lambda/tcss562-sync-transform

## 初始化前只读检查结果

- 当前调用身份为 root。
- tlq-dev 用户不存在；tcss562-transform-exec 角色不存在。
- 当前自定义托管策略只有四份 AWSLambdaBasicExecutionRole-*，不修改或复用。
- 已发现 service-role/tcss562-tlq-transform-role-thhapzeh，及两个 TLQ Load 角色，全部保留原样。
- AWS CLI 2.39.5 支持 aws login；不需要长期 Access Key。

## 已批准并完成的 IAM 变更

1. CreateUser：tlq-dev，路径 /，不创建 LoginProfile 或 Access Key。
2. CreateRole：tcss562-transform-exec，使用 transform-execution-trust.json。
3. PutRolePolicy：仅在新角色上添加内联策略 tlq-transform-logs，内容为 transform-execution-logs.json。
4. CreatePolicy：创建客户托管策略 tlq-transform-deploy，内容为 tlq-dev-transform-deploy.json。
5. AttachUserPolicy：将新建的 tlq-transform-deploy 仅附加给新用户 tlq-dev。
6. PutUserPolicy：仅在新用户上添加内联策略 tlq-local-login，内容为 tlq-dev-local-login.json。

每步写入前重新核验目标；如果发现同名资源或策略，停止并报告，不覆盖。
仅创建上述一份客户托管部署策略，不附加 AdministratorAccess，不修改其他用户、角色或策略。
不创建 Lambda、URL、日志组、S3、数据库或任何长期密钥。
开发用户没有 IAM 权限编辑能力，无法创建角色、修改角色策略、信任策略或添加用户权限。
部署权限仅针对上述单个函数、日志组和角色。无 Lambda AddPermission，因此不能用它添加公开资源策略。
Function URL 创建/更新限制为 AWS_IAM；同账户调用权限直接授予开发用户，不需要增加公开资源策略。
未授予删除函数/日志组、全局资源列表或 IAM 控制台浏览权限。命令行按已知资源名部署，不以控制台完整管理为目标。
日志组由开发身份以后部署时预先创建并设置保留期，Lambda 执行角色不需要 CreateLogGroup。
local-login 策略只允许本区域、本账户、本机 localhost CLI 登录，不允许 remote 登录。
这些权限须受账户 SCP、其他显式 Deny 等上层控制影响；部署前再验证。

## 初始化后只读验证

- 用户 ARN：arn:aws:iam::803964124311:user/tlq-dev。
- 用户仅附加 tlq-transform-deploy，内联策略仅 tlq-local-login，所属组数量为 0。
- 执行角色仅有 tlq-transform-logs 内联策略，无附加托管策略。
- 部署、CLI 登录、角色信任及角色日志四份策略均与批准文件完全一致。
- Access Key 数量为 0；LoginProfile 不存在，尚未启用控制台密码。
- 不修改已有角色、不创建 Lambda；CLI 开发身份仍需用户完成下述浏览器登录后验证。

## 用户自行完成控制台登录与 CLI 登录

审批并完成 IAM 初始化后：
1. 在 AWS IAM 网页中打开 tlq-dev → 安全凭证，启用管理控制台访问并自行设置密码。
   不向助手提供密码。当前草案不授予改密码权限；如设置首次登录强制改密码，需另外审批用户自助改密码权限。
2. 用独立浏览器会话访问 https://803964124311.signin.aws.amazon.com/console ，选择 IAM 用户 tlq-dev 登录。
3. 在自己的 Bash 运行：
   aws login --profile tcss562-dev --region us-west-2
4. 浏览器明确选择 tlq-dev 会话，不能选 root。
5. 验证：
   aws sts get-caller-identity --profile tcss562-dev --region us-west-2
   预期 Arn：arn:aws:iam::803964124311:user/tlq-dev
   aws configure get region --profile tcss562-dev
6. 之后所有部署命令显式传 --profile tcss562-dev；本次不部署。

如果账号策略或登录端点不允许 IAM 用户浏览器 CLI 登录，停止并报告错误，改为评估 Identity Center；
不会自动创建长期 Access Key。

参考：
- https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-sign-in.html
- https://docs.aws.amazon.com/aws-managed-policy/latest/reference/SignInLocalDevelopmentAccess.html
- https://docs.aws.amazon.com/lambda/latest/dg/urls-auth.html
- https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_use_passrole.html
