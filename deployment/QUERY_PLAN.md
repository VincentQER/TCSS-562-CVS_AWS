# Query 最小方案与后续对齐草案

当前已实现 provisional-v1，具体暂定规则、HTTP 接口和验证命令见 [Query 本地实现](QUERY.md)。
本草案保留方案比较和需双方最终对齐事项；不应将下方历史建议误认为尚无代码或已达成最终业务约定。

## 已确认

- 所有查询必须限定一个 run_id，缺失或空白直接拒绝，不允许查询所有实验。
- 支持州、年份过滤及分组，指标包含 COUNT、支付金额 SUM、AVG。
- 源文件重复行保留在统计输入中，不按 Record_ID 去重，不使用 DISTINCT 去重。
- 使用参数化查询和服务端字段白名单，不接受客户端 SQL、表达式、表名或排序片段。
- 金额当前是文本；不得依赖 MySQL 隐式字符串到数字转换，也不修改已有数据。
- 优先在 MySQL 内完成 GROUP BY、COUNT、SUM、AVG；不默认将明细全部读取到 Java 聚合。
- Load/Query 已完成本地真实 MySQL 验证；Query 新报告 5ae74120-0604-41b6-9c22-a1fa13f924f3 为 PASSED，首次失败报告保留原样。Aurora、云端 Load/Query 和性能尚未验证。

## 建议的最小接口（待队友确认）

建议 POST application/json，实验编号沿用暂定 X-TLQ-Run-Id。
请求体暂建议 filters、groupBy、metrics；filters 仅接受 state、year。
groupBy 允许空数组、state、year 或二者组合；metrics 仅允许 count、sum、avg。
拒绝未知字段、重复分组项或指标、无效类型及任意自定义表达式。

| API 白名单名称 | 固定字段/含义 |
| --- | --- |
| state | recipientState |
| year | programYear |
| count | 建议为全部匹配行数，类似 COUNT(*) |
| sum / avg | totalAmountOfPaymentUsdollars |

这些 API 名称、COUNT 精确定义、过滤格式及比较规则仍需确认，不是最终契约。

## 数据库聚合与参数化查询

WHERE run_id = ? 必须存在；州、年份条件由服务端添加固定列名和占位符，所有过滤值绑定为字符串，避免文本年份隐式数字比较。
表名来自受限配置；GROUP BY、指标表达式及 ORDER BY 只从固定白名单映射，不能拼接客户端 SQL。
MySQL 执行筛选、GROUP BY、COUNT(*)、SUM(amount_decimal)、AVG(amount_decimal)，JDBC 只读取聚合结果及校验计数。
不做 DISTINCT，不通过过滤非法金额行来改变 COUNT 或掩盖错误。
仅 COUNT 的请求不要求解析金额；SUM/AVG 的请求才应用已确认的金额规则。
查询应在本轮 Load 全部成功后运行；不得将中途或提交结果不确定的实验作为完整统计。
任何响应数量/字节限制应在返回前明确报错，不截断参与统计的数据或悄悄丢弃分组。

## 两种金额处理方案（选择尚待双方确认）

两种方案都保持原 75 字段 JSON 不变、原始金额文本不变、源重复记录不变。

| 项目 | 方案一：Load 写额外类型化金额列 | 方案二：Query 校验后显式转换 |
| --- | --- | --- |
| 存储 | 在原文本列之外新增 amount_decimal DECIMAL(p,s) NULL | 保持当前文本表，不新增列 |
| 校验位置 | Load 全批校验文本语法、范围和小数位，Java BigDecimal 转换后 setBigDecimal | MySQL 在筛选后的记录上分类空值/合法/非法，再安全 CASE + CAST |
| 聚合 | 直接对额外 DECIMAL 列 GROUP BY / SUM / AVG | 对校验后的 DECIMAL 表达式 GROUP BY / SUM / AVG |
| 优点 | 每次加载校验一次，重复查询较直接，数值不易混入脏值 | 不迁移现有数据，保留已验证的 Load 和表结构，适合当前阶段 |
| 代价 | 要调整表、Load 及相关测试；已有数据另需经批准回填或重新实验 | 每次查询重复校验/转换，消耗数据库 CPU；SQL 更复杂 |
| 非法非空金额 | 建议整批 Load 拒绝并回滚 | 建议整次 Query 拒绝，不返回部分聚合 |
| 对已有数据 | 本次不新增列或回填；未回填与空金额不能都无区别地记成 NULL | 可只读查询已有实验记录 |

当前阶段暂建议方案二，以满足不修改已有数据的约束；长期是否切换方案一由双方确认，不在本次实施。
方案一必须在原始金额文本与派生列之间保持一致；不能由数据库隐式转换 setString，也不能只凭 DECIMAL 列 NULL 判断已有数据是否已校验。
如果以后允许保留非法金额再加载，需另行定义有效状态，不能将非法值伪装为空值；当前不默认采用该策略。

### 方案二的明确执行流程（仅设计，未执行）

1. 在同一条只读 SQL 的 CTE/派生查询中，先按 run_id 和可选州、年份过滤，获得固定查询范围。
2. 对每行原始金额分类：MISSING、VALID、INVALID。先完成完整字符串语法、小数位和数值范围检查，不能用 CAST 的成功与否作为校验。
3. VALID 判定需使用全串匹配，并排除任何不允许字符（含末尾换行）；不能仅用允许匹配最后换行前位置的宽松正则锚点。
4. 按最终 p、s 对整数/小数位数及边界进行文本校验，再转换；前导零计数规则也需一致。不先 CAST 再检查溢出、截断或舍入。
5. 用 CASE WHEN VALID THEN CAST(raw_amount AS DECIMAL(p,s)) ELSE NULL END 生成 amount_decimal，并独立保留 invalid 标记。
6. CAST 必须位于已校验的 CASE 分支内，不依赖 WHERE 先执行、CTE 必然物化或 AND 表达式从左到右等优化器执行假设。
7. MySQL 按白名单字段分组，同时返回 COUNT(*)、COUNT(amount_decimal)、SUM(amount_decimal)、AVG(amount_decimal)、invalidCount。
8. 任一分组 invalidCount > 0 时，handler 拒绝整个金额查询，例如 422 / INVALID_AMOUNT；不把非法金额当 0、不当缺失值静默忽略、不返回部分分组。
9. 先读完并核对所有分组，再构造成功响应；任何转换警告、累计溢出或无法验证的结果均报错，不输出部分结果。

分类、转换和统计在同一条 SQL 及同一读快照中完成，避免先查非法值、再聚合期间数据变化。
SQL 必须保留缺失和非法行用于计数，不能在 WHERE 中仅保留 VALID 行。无匹配时校验计数需显式按零处理。
生产 SQL 已按此思路实现，仍需手动真实 MySQL 测试证明非法值和边界值不会进入不安全 CAST 分支。

## 空值、金额精度及舍入建议（以下全部待双方确认）

1. 仅接受普通十进制；不允许指数、千位逗号、货币符号或尾随字符。负数、显式正号及前导零规则需统一。
2. 暂建议不自动 trim；空字符串和 SQL NULL 视为缺失金额，纯空格视为非法。当前 Load 只写字符串，SQL NULL 为防御性情况。
3. 单笔暂建议 DECIMAL(20,2)：最多 18 位整数、2 位小数；语法、范围或小数位不符时拒绝，不逐笔舍入、截断或夹取到最大值。
4. COUNT 暂建议统计全部匹配行；SUM/AVG 忽略缺失金额，COUNT(amount_decimal) 作为 amountCount，供客户端核对 AVG 分母。
5. 非法金额策略如上表：方案一拒绝本批 Load，方案二拒绝本次金额 Query。两者在脏数据存在时的结果可用性不同，比较实验需统一数据/错误口径。
6. SUM 使用 DECIMAL 精确累计，不转 FLOAT/DOUBLE；总和可能超出单笔范围，不能把 SUM 强制再 CAST 回 DECIMAL(20,2)。累计上限及溢出错误仍需确认。
7. 暂建议 SUM 返回两位小数字符串；AVG 返回四位小数字符串，并在最终输出时采用 HALF_UP，均为建议而非已定规则。
8. MySQL AVG/十进制除法的中间精度受 div_precision_increment 等规则影响；不能先低精度 AVG 再舍入而忽略双重舍入。最终需约定会话精度、输出位数和负数中点规则，并测试边界。
9. 如需严格按精确 SUM / amountCount 最后一步舍入，可仅对数据库已聚合的每组 SUM/COUNT 用 Java BigDecimal 做最终除法格式化；不是读取明细到 Java 聚合。是否允许此格式化路径仍待确认，MySQL 仍负责 GROUP BY、COUNT、SUM、AVG。
10. 暂建议分组内没有有效金额时 SUM/AVG 为 JSON null，而不是 0；无分组且无匹配行时 count=0、amountCount=0、sum=null、avg=null，有分组时返回空数组。

参考：MySQL 的 DECIMAL 参数 SUM/AVG 返回精确数值类型，但显式 CAST 本身不能替代输入校验。
- https://dev.mysql.com/doc/refman/8.4/en/cast-functions.html
- https://dev.mysql.com/doc/refman/8.4/en/aggregate-functions.html
- https://dev.mysql.com/doc/refman/8.4/en/precision-math-rounding.html

## 待队友确认清单

- 请求方法、头/体字段名称，run_id 格式，允许的州/年份格式和精确匹配方式。
- 州大小写、空白、空州/空年份的分组行为；是否规范化，以及未知值如何表示。
- COUNT 是否定义为 COUNT(*)，金额缺失是否排除出 SUM/AVG，是否返回 amountCount。
- 金额语法、正负号、trim、范围、非法值策略及整次错误状态码。
- 单笔精度、SUM/AVG 输出小数位、HALF_UP 或其他舍入模式，以及全空/无匹配结果。
- 分组键和响应字段名称、稳定排序、分组数量/响应字节上限；超限应报错，不截断统计。
- 在两种数据库聚合方案中选择；若使用额外列，另行批准表/Load 调整及已有数据处理，不默认为已授权。
- AVG 的中间精度、是否允许仅在 Java 对每组已聚合结果做最终除法格式化，以及双方的精确舍入口径。
- Aurora 引擎版本、Load/Query 区域、网络及权限仍待定，本次不创建资源。

## 验证状态与后续计划

48 项单元测试通过；用户已隐藏输入密码运行新版只读真实 MySQL 测试，9 项检查通过（含 30 个聚合/过滤查询对照），详见进度文档。首次失败历史保留。

单元测试覆盖必填 run_id、白名单拒绝、参数绑定、金额语法、精度、重复行及空结果。
真实 MySQL 测试需验证 SQL GROUP BY/COUNT/SUM/AVG、非法值在 CASE 中不会被转换为 0、超范围值不触发不安全 CAST、缺失值分母及舍入边界。
在已加载 run_id 上只读核对州/年份过滤、各分组行数和独立参考结果；任何新增边界夹具需另行授权，不改已有数据。
通过后再准备 HTTP handler 验证及单独的 Aurora 验证，不把本地 MySQL 结果当作 Aurora 或性能结论。
