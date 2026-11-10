# 两套 TLQ Pipeline 共享 SQL 与输入输出草案

版本：shared-query-draft-v1，对应当前客户端代码 QueryRules 的 provisional-v1。
已确认的是“两套 pipeline 使用相同查询”；下述金额、空值、精度、舍入及接口细节仍需队友逐项确认，不能视为最终共同契约。
本文件只整理已有实现，没有修改代码、表或数据，也没有执行 SQL 或部署。

## 适用范围与验证证据

- 来源：JdbcQueryService、QueryRules、QueryRequest、QueryUrlHandler。
- 本地 MySQL 8.4.9 / Connector/J 8.4.0 已验证，Query 成功报告为 query-mysql-it-5ae74120-0604-41b6-9c22-a1fa13f924f3.json。
- 报告有 9 项通过检查，包含六个已加载 run 的 30 次聚合/过滤对照。Aurora 和云端 Load/Query 尚未验证。
- 此处小样例是为双方手工对照而整理的预期示例，不是上述报告保存的真实业务输出，也未新增为数据库夹具。
- 输入仍为原 75 字段字符串 JSON；金额字段 totalAmountOfPaymentUsdollars，不添加客户端类型化字段。
- 源重复行保留，Record_ID 无去重或唯一约束，SQL 不使用 DISTINCT。
- 必须限定 run_id；同步与异步实验可用不同 run_id，但对相同源数据执行相同业务查询后结果应一致。

## 字段、参数与安全约定

| API 名称 | 当前数据库列/含义 |
| --- | --- |
| run_id（请求头 X-TLQ-Run-Id） | run_id，必填且非空白 |
| state | recipientState，文本 |
| year | programYear，文本，过滤值例如 "2018"，不是 JSON 数字 |
| count | COUNT(*)，含重复行和空金额行 |
| sum / avg | 对校验后的支付金额 DECIMAL 聚合 |

允许分组：不分组、state、year、state+year（也支持请求白名单顺序 year+state）。
过滤仅允许 state、year；可省略某项条件。禁止客户端提供 SQL、表名、表达式或自定义排序。
表名由服务端配置映射，以下用当前本地 payments_raw；另一 pipeline 若表名不同，只能由服务端替换为受限标识符。
所有 ? 均为 JDBC PreparedStatement 参数，不能直接粘贴到 mysql 客户端当作完整可执行 SQL。
州/年份参数使用 setString；不自动 trim 或转大写，比较/排序取决于数据库排序规则。
本地夹具为 utf8mb4_0900_bin；正式库字符集、排序规则、空州/空年份的语义仍需统一。

## 共享 SQL A：仅 COUNT(*)

不带过滤的当前 SQL：

```sql
WITH filtered AS (
  SELECT recipientState AS `state`, programYear AS `year`,
         totalAmountOfPaymentUsdollars AS raw_amount
  FROM payments_raw
  WHERE run_id = ?
)
SELECT COUNT(*) AS row_count
FROM filtered;
```

参数顺序：[run_id]。仅 COUNT 不检查金额格式，非法金额也不会阻止记录计数。
加过滤时在 filtered 的 WHERE 中增加固定片段，不改变记录来源或使用数值隐式比较：

```sql
WHERE run_id = ?
  AND recipientState = ?
  AND programYear = ?
```

启用两项过滤后的参数顺序：[run_id, state, year]。若只启用一项，只添加该列条件和相应参数；省略条件与传空字符串不同。
按州计数时 SELECT 增加 `state`，并添加 GROUP BY `state` ORDER BY `state`；按年或双字段同理，字段只来自白名单。

## 共享 SQL B：按州和年份统计 COUNT/SUM/AVG

以下是当前实现的同等格式化 SQL，保留分类和非法计数，不把非法行过滤掉：

```sql
WITH filtered AS (
  SELECT recipientState AS `state`, programYear AS `year`,
         totalAmountOfPaymentUsdollars AS raw_amount
  FROM payments_raw
  WHERE run_id = ?
),
classified AS (
  SELECT *,
    CASE
      WHEN raw_amount IS NULL OR OCTET_LENGTH(raw_amount) = 0 THEN 0
      WHEN REGEXP_LIKE(raw_amount, ?, 'c')
       AND NOT REGEXP_LIKE(raw_amount, ?, 'c') THEN 1
      ELSE 2
    END AS amount_status
  FROM filtered
),
typed AS (
  SELECT *,
    CASE WHEN amount_status = 1
         THEN CAST(raw_amount AS DECIMAL(20,2))
         ELSE NULL
    END AS amount_decimal
  FROM classified
)
SELECT `state`, `year`,
       COUNT(*) AS row_count,
       COUNT(amount_decimal) AS amount_count,
       SUM(amount_decimal) AS amount_sum,
       AVG(amount_decimal) AS amount_avg,
       COALESCE(SUM(CASE WHEN amount_status = 2 THEN 1 ELSE 0 END), 0)
         AS invalid_count
FROM typed
GROUP BY `state`, `year`
ORDER BY `state`, `year`;
```

参数依次为：

1. run_id。
2. `^-?[0-9]{1,18}([.][0-9]{1,2})?$`。
3. `[^0-9.-]`。

若增加州、年份过滤，固定 WHERE 条件仍放在 filtered 中，参数顺序变为 [run_id, state, year, 金额正则, 非法字符正则]。
按州聚合：最终 SELECT 仅保留 `state` 分组列，GROUP BY / ORDER BY 也仅保留 `state`；按年同理。
无分组：最终 SELECT 去掉全部分组列，并去掉 GROUP BY / ORDER BY；其他聚合及校验表达式不变。
以上变体已由当前测试覆盖；不使用 GROUP BY ?，不把绑定参数当作列名。

amount_status：0=缺失，1=合法，2=非法。CAST 只在合法 CASE 分支内执行，不依赖 WHERE/AND 执行顺序或 CTE 必然物化。
第二个正则会拒绝末尾换行等不允许字符，避免单靠 $ 锚点放过尾随换行。
同一 SQL 完成筛选、分类和聚合；不得改为先检查一次、再用另一快照查询未校验文本。

## 必须配套的结果检查与格式化（仅 SQL 不足以实现完整契约）

- 只要某一结果组 invalid_count > 0，整个金额 Query 失败；不能返回该组或其他已计算组的部分成功结果。
- 不可将非法值改成 0；也不能将 amount_decimal=NULL 的非法值与缺失值混同后静默忽略。
- SQLWarning、转换溢出或其他 SQL 错误均失败，不输出部分结果，不回显原文或 JDBC 异常。
- JSON count 来自 row_count，amountCount 来自 amount_count，都是整数。
- JSON sum 为 amount_sum 的精确十进制字符串，固定两位小数；不重新 CAST 成单笔 DECIMAL(20,2)。
- MySQL 实际计算并返回 amount_avg。但 JSON avg 当前使用数据库已聚合的 amount_sum / amount_count，最后一次舍入到四位，HALF_UP。
  当前 Java 使用 BigDecimal.divide；只格式化每组聚合结果，不读取明细重新聚合。
- 另一 pipeline 必须采用相同最终除法/舍入，不能直接 ROUND 一个中间精度不足的 SQL AVG，否则可能双重舍入而不一致。
- 本地已记录 div_precision_increment=4；仍不能将它当作两套环境永远相同的保证。
- count-only 请求只返回选定分组键与 count；金额请求额外返回 amountCount，并只返回选定的 sum/avg/count 指标。

## 暂定金额及空值规则：仍待队友确认

| 输入情况 | 当前 provisional-v1 行为 |
| --- | --- |
| 空字符串或 SQL NULL | 缺失，保留行；COUNT 计入，SUM/AVG 不计入 |
| "0"、"0.00" | 合法零金额，计入 amountCount 和 AVG 分母 |
| 纯空格、首尾空格、尾随换行 | 非法，不 trim |
| "-2.50" | 合法负数；允许负号，不允许正号 |
| "00.01" | 合法前导零，但整数部分字符仍计入 18 位上限 |
| "+1"、"1e2"、"1,000"、"$1"、"1x" | 非法 |
| ".1"、"1."、"1.234" | 非法；必须有整数位，最多两位小数 |
| 超过 18 位整数或非 ASCII 数字 | 非法，即使前导零使数值看起来未超范围 |

单笔合法范围对应 DECIMAL(20,2)，最大绝对值 999999999999999999.99；校验位数后才转换，不截断、不夹取、不逐笔舍入。
SUM 允许超过单笔范围，但数据库累计溢出仍需明确失败。不得使用 FLOAT/DOUBLE。
AVG 分母为 amountCount，不是 COUNT(*)；重复的合法金额各自参与累计和分母。
全部金额缺失的分组 sum=null、avg=null、amountCount=0；count 仍为实际行数。
无分组且无匹配行返回一条 count=0、amountCount=0、sum=null、avg=null；有分组且无匹配行返回 results=[]。

## 输入输出示例（人工小样例，非新增数据库数据）

下表仅投影 75 字段中的三列；不是可以直接提交给 Load 的完整 75 字段 JSON。
全部属于 run_id=demo-shared-01。第 1、2 行假定完整 75 字段完全相同，仍各保留一行。

| 源行 | recipientState | programYear | totalAmountOfPaymentUsdollars |
| --- | --- | --- | --- |
| 1 | WA | 2018 | "10.00" |
| 2 | WA | 2018 | "10.00" |
| 3 | WA | 2018 | "" |
| 4 | WA | 2019 | "5.55" |
| 5 | CA | 2018 | "-2.50" |
| 6 | CA | 2018 | "0.00" |
| 7 | CA | 2019 | "" |

其他 run 即使有相同记录，也不进入本次结果。
客户端暂定请求统一为 POST application/json，头 X-TLQ-Run-Id: demo-shared-01。

### 仅计数

请求：`{"metrics":["count"]}`。

```json
{"runId":"demo-shared-01","rulesVersion":"provisional-v1","results":[{"count":7}]}
```

### 不分组的金额聚合

请求：`{"metrics":["count","sum","avg"]}`。

```json
{"runId":"demo-shared-01","rulesVersion":"provisional-v1","results":[{"count":7,"amountCount":5,"sum":"23.05","avg":"4.6100"}]}
```

### 按州分组

请求：`{"groupBy":["state"],"metrics":["count","sum","avg"]}`，results 为：

```json
[
  {"state":"CA","count":3,"amountCount":2,"sum":"-2.50","avg":"-1.2500"},
  {"state":"WA","count":4,"amountCount":3,"sum":"25.55","avg":"8.5167"}
]
```

### 按年份分组

请求：`{"groupBy":["year"],"metrics":["count","sum","avg"]}`，results 为：

```json
[
  {"year":"2018","count":5,"amountCount":4,"sum":"17.50","avg":"4.3750"},
  {"year":"2019","count":2,"amountCount":1,"sum":"5.55","avg":"5.5500"}
]
```

### 按州和年份分组

请求：`{"groupBy":["state","year"],"metrics":["count","sum","avg"]}`，results 为：

```json
[
  {"state":"CA","year":"2018","count":2,"amountCount":2,"sum":"-2.50","avg":"-1.2500"},
  {"state":"CA","year":"2019","count":1,"amountCount":0,"sum":null,"avg":null},
  {"state":"WA","year":"2018","count":3,"amountCount":2,"sum":"20.00","avg":"10.0000"},
  {"state":"WA","year":"2019","count":1,"amountCount":1,"sum":"5.55","avg":"5.5500"}
]
```

### 同时过滤州和年份

请求：`{"filters":{"state":"WA","year":"2018"},"groupBy":["state"],"metrics":["count","sum","avg"]}`，results 为：

```json
[{"state":"WA","count":3,"amountCount":2,"sum":"20.00","avg":"10.0000"}]
```

### 非法金额与空结果

另一个 run 仅有金额 "12oops" 的一行：count-only 返回 count=1；请求 sum 或 avg 则整个 HTTP 请求返回 422：

```json
{"error":"INVALID_AMOUNT","message":"A nonempty amount is invalid; no aggregate results returned"}
```

不存在的 run 不分组金额查询：results=[{"count":0,"amountCount":0,"sum":null,"avg":null}]。
不存在的 run 按州分组：results=[]。两者都不是数据库或 run_id 参数错误。

## 队友确认清单

- 本文件字段映射、run_id 传递、白名单、COUNT(*) 和重复行语义是否完全一致。
- 是否接受当前负号、前导零长度限制、不 trim、空金额/非法金额规则及整个请求失败策略。
- 是否采用 DECIMAL(20,2)、sum 两位、avg 四位、HALF_UP，以及基于 SUM/amountCount 的最终精确格式化。
- 正式字符集/排序规则、州/年份大小写与空字符串分组语义、稳定排序及 JSON 字段名。
- count-only 是否保持不校验金额，空匹配和全空金额结果是否一致。
- 共享测试数据、代表性 run、性能实验查询清单、负载与统计范围；两套 run_id 可不同，但查询条件和输入内容须对齐。
- 当前应用限制（64 KiB 请求、10,000 分组、5 MiB 完整响应）是否需要共同约定；超限报错，不截断统计。
- 最终 Aurora 引擎版本和区域兼容性仍需验证。本地通过不代表云端或最终规则已确认。

本次仅新增共享说明并链接到交接文档；没有修改实现、执行 SQL、创建资源、部署、提交或推送。
