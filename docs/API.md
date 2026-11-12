# API 契约 v0.4.0

金额一律整数分（`3.1 元 = 310`）。
除 `/auth/*`、`/health` 外都需要 `Authorization: Bearer <token>`；
`/admin/**` 额外要求当前用户 `role = admin`（每次请求读库校验）。
令牌被吊销或账号停用时返回 401，body 为 `{"error":"TOKEN_REVOKED"}` 或
`{"error":"ACCOUNT_DISABLED"}`。

## 认证

### POST /auth/register
```json
{ "email": "a@b.com", "password": "至少8位", "nickname": "可选" }
```
→ `{ "token": "...", "role": "admin" | "user" }`
错误：`EMAIL_TAKEN` / `EMAIL_INVALID` / `WEAK_PASSWORD` / `SIGNUP_DISABLED`

### POST /auth/login
```json
{ "email": "a@b.com", "password": "..." }
```
→ `{ "token": "...", "role": "..." }`
错误：`BAD_CREDENTIALS` / `ACCOUNT_DISABLED`

### POST /auth/wx/login
```json
{ "code": "wx.login 的 code" }
```
已绑定 → `{ "token": "...", "role": "..." }`
未绑定 → `{ "needBind": true, "bindToken": "10分钟有效" }`

### POST /auth/wx/bind
```json
{ "bindToken": "...", "email": "a@b.com", "password": "..." }
```
→ `{ "token": "...", "role": "..." }`
错误：`BIND_TOKEN_INVALID` / `BAD_CREDENTIALS` / `WECHAT_ALREADY_BOUND`

### POST /auth/wx/unbind     （需登录）
→ `{ "ok": true }`

### POST /auth/logout     （需登录）
→ `{ "ok": true }`，自增 `token_version`，该账号已发出的令牌全部失效

### GET /auth/me       （需登录）
→ `{ "id":1, "email":"...", "nickname":"...", "role":"admin", "wxBound":true }`

### POST /auth/password/forgot
```json
{ "email": "a@b.com" }
```
→ `{ "ok": true }`（无论邮箱是否存在都返回成功；链接打印在服务端日志）

### POST /auth/password/reset
```json
{ "token": "重置票据", "password": "新密码" }
```

## 账本

### POST /api/parse
```json
{ "text": "火锅240两个人AA" }
```
→
```json
{
  "items": [{
    "type": "expense", "amount": 12000, "category": "food",
    "merchant": null, "note": "火锅AA",
    "occurredAt": "2026-11-12T10:30:00+08:00",
    "suggest": null, "confidence": 0.35,
    "flags": { "inText": false, "derived": true }
  }],
  "raw": "{...}",
  "model": "deepseek-chat",
  "createdCategories": ["宠物"]
}
```
`createdCategories` 是本次解析中 AI 自动新建的分类**名称**（没有则为空数组），
新建的分类会出现在 `GET /api/categories` 里。
错误：`NO_LLM_CONFIG` / `LLM_FAILED` / `EMPTY_TEXT` / `TEXT_TOO_LONG` /
`AI_QUOTA_EXCEEDED`（当天解析次数用完，429，`message` 里带剩余说明）

### POST /api/transactions/batch
```json
{ "items": [ ... ], "text": "原始输入", "raw": "模型原始返回", "model": "deepseek-chat",
  "edited": false, "source": "text" }
```
`raw` / `model` / `edited` / `source` 可选。`source` 只认 `text`（AI 解析）与
`manual`（手动记账）；只有带 `text` 的请求会写 `parse_feedback`。
→ `{ "ids": [1,2] }`

### GET /api/transactions
| 参数 | 说明 |
|---|---|
| `month=2026-11` | 整月；与 `from/to` 同时给时以 `from/to` 为准 |
| `from=2026-11-01` / `to=2026-11-30` | 闭区间（传一个会 400 `BAD_RANGE`） |
| `q=面馆` | 模糊搜备注、商家、分类名（`% _ \` 会被转义） |
| `type=expense\|income` | 只看支出或收入，其它值 400 `BAD_TYPE` |
| `category=food` | 只看某个分类 |
| `limit=500` | 1–2000，默认 500；都不传时返回最近 500 条 |

→ `[{ "id":1, "type":"expense", "amount":310, "category":"shopping",
      "merchant":"淘宝", "note":"超红", "occurredAt":"..." }]`

### PATCH /api/transactions/{id}
```json
{ "amount": 3200, "category": "food", "note": "改过的午饭",
  "occurredAt": "2026-11-12 12:30", "merchant": "面馆", "type": "expense" }
```
字段都可选，只改传了的。`occurredAt` 接受 ISO 偏移（`2026-11-12T12:30:00+08:00`）、
`2026-11-12T12:30:00`、`2026-11-12 12:30` 三种写法。→ 返回更新后的整行。
错误：`NOT_FOUND` / `BAD_TYPE` / `AMOUNT_INVALID` / `BAD_CATEGORY` / `BAD_TIME` / `TEXT_TOO_LONG`

### DELETE /api/transactions/{id}
→ `{ "ok": true }`（软删除）

### GET /api/summary?month=2026-11
`month` 可省略，默认当月；格式不对返回 400 `{"error":"BAD_MONTH"}`。
→ `[{ "type":"expense", "category":"food", "total":12000, "n":3 }]`

### GET /api/report
参数与 `/api/transactions` 相同：`month`，或 `from` + `to`（闭区间，最长 731 天）。
`month` 可省略，默认当月；格式不对 400 `BAD_MONTH`；`from > to` 400 `BAD_RANGE`；
区间过长 400 `RANGE_TOO_LONG`。
```json
{
  "from": "2026-11-01", "to": "2026-11-30", "days": 30,
  "month": "2026-11",
  "expense": 275040, "income": 1200000, "count": 10,
  "prevFrom": "2026-10-02", "prevTo": "2026-10-31",
  "prevExpense": 222200, "prevIncome": 0, "prevCount": 2,
  "byCategory": [{ "category": "housing", "type": "expense", "total": 220000, "n": 1 }],
  "byDay": [{ "day": "2026-11-01", "expense": 220000, "income": 0 }],
  "trend": [{ "month": "2026-06", "expense": 222500, "income": 0 }],
  "topMerchants": [{ "name": "房租", "total": 220000, "n": 1 }]
}
```
- `prevFrom/prevTo` 是紧挨着本区间的前一段同样长度的区间
- `trend`：区间 ≤62 天给最近 6 个月；更长时给区间内逐月（最多 13 个）
- `topMerchants` 最多 6 个，`name` 取「商家 → 备注 → 分类 id」中第一个非空值
- 金额单位都是分

### GET /api/transactions/export
参数 `month` 或 `from` + `to`（`from/to` 优先）；`month` 省略或传 `all` 导出全部。
导出 CSV，`Content-Type: text/csv; charset=UTF-8`，
`Content-Disposition: attachment; filename="ledger-2026-11-01_2026-11-30.csv"`。
带 UTF-8 BOM，Excel 直接打开不乱码；列为
`日期时间,类型,金额,分类,备注,商家,来源`（来源为「手动」/「AI」，金额两位小数）。

### GET /api/categories
→ `[{ "id":"food", "name":"餐饮", "type":"expense", "icon":"🍜", "custom":false }]`
系统分类在前、自建分类在后；`custom` 表示是否当前用户自建。

### POST /api/categories
```json
{ "name": "宠物", "type": "expense", "icon": "🐱" }
```
→ `{ "id":"u3_a1b2c3d4", "name":"宠物", "type":"expense", "icon":"🐱", "custom":true }`
名字 ≤12 字；与系统分类或自己的分类同名时直接返回已有分类（不报错）。
错误：`INVALID_INPUT` / `BAD_TYPE`

### DELETE /api/categories/{id}
→ `{ "ok": true }`，只能删自己的分类；已记的账不受影响（`transactions.category` 无外键）。

## AI（需登录）

### POST /api/ai/summary
```json
{ "from": "2026-11-01", "to": "2026-11-30" }
```
也可以只传 `{ "month": "2026-11" }`（缺省当月）。
→
```json
{
  "text": "这个月你花了 2531.40 元，收入 8000 元……",
  "model": "deepseek-flash",
  "from": "2026-11-01", "to": "2026-11-30",
  "expense": 253140, "income": 800000, "count": 9,
  "summaryUsed": 1, "summaryLimit": 20
}
```
- 3–5 句中文复盘：区间收支、上一段对比、分类结构、单日峰值、商家、建议
- 区间没有账目 → 400 `NO_DATA`；当天总结次数用完 → 429 `AI_QUOTA_EXCEEDED`
- 还会返回 `BAD_RANGE` / `RANGE_TOO_LONG` / `NO_LLM_CONFIG` / `LLM_TIMEOUT`

### GET /api/ai/usage
→ `{ "parseUsed": 0, "parseLimit": 50, "summaryUsed": 1, "summaryLimit": 20 }`
按「用户 + 自然日（Asia/Shanghai）」统计，解析和总结分开计数。

## 管理（需 admin）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/users?q=` | 用户列表 |
| PATCH | `/admin/users/{id}` | `{"role":"admin"}` 或 `{"status":"disabled"}` |
| POST | `/admin/users/{id}/reset-password` | → `{"tempPassword":"..."}` |
| GET | `/admin/llm-configs` | Key 只返回尾号 |
| POST | `/admin/llm-configs` | `{name,baseUrl,model,apiKey,isDefault}` |
| PATCH | `/admin/llm-configs/{id}` | apiKey 留空 = 不修改 |
| DELETE | `/admin/llm-configs/{id}` | |
| POST | `/admin/llm-configs/{id}/test` | → `{ok,latencyMs,reply}` |
| GET | `/admin/stats` | 用户数、账目数、本月支出 |
| GET | `/admin/audit-logs` | 最近 100 条管理操作 |

错误：`CANNOT_DEMOTE_SELF` / `USER_NOT_FOUND` / `CONFIG_NOT_FOUND`

## GET /health
→ `{ "ok": true, "version": "0.4.0", "db": "mysql" }`

## 错误码

统一返回 `{"error":"CODE"}`；带上下文时多一个 `"message"` 字段（目前用于把上游模型
返回的报错原样透出来）。

| 状态码 | error | 含义 |
| --- | --- | --- |
| 400 | `BAD_REQUEST` / `BAD_MONTH` / `BAD_RANGE` / `RANGE_TOO_LONG` / `BAD_TYPE` / `AMOUNT_INVALID` / `BAD_CATEGORY` / `BAD_TIME` / `NO_DATA` / `INVALID_INPUT` / `EMPTY_TEXT` / `TEXT_TOO_LONG` | 参数缺失、类型不对、JSON 损坏、月份格式错误、日期区间非法或超 731 天、改账字段不合法、该区间没有账目、解析文本为空或超 500 字 |
| 401 | `UNAUTHORIZED` / `TOKEN_REVOKED` / `ACCOUNT_DISABLED` / `WX_LOGIN_FAILED` | 未登录、登出/改密后旧令牌失效、账号被停用 |
| 403 | `FORBIDDEN` | 需要 admin |
| 404 | `NOT_FOUND` | 路由不存在 / 账目不存在 |
| 429 | `RATE_LIMITED` / `AI_QUOTA_EXCEEDED` | 触发限流 / 当天 AI 解析或总结次数用完 |
| 503 | `NO_LLM_CONFIG` / `AI_NOT_CONFIGURED` | 后台没有启用的大模型配置 |
| 502 | `EMPTY_MODEL_OUTPUT` / `LLM_FAILED` / `LLM_UPSTREAM_<状态码>` | 模型返回空内容 / 两个候选模型都失败 / 上游模型接口报错（`message` 里带上游原文） |
| 504 | `LLM_TIMEOUT` | 连模型接口超时（默认 5s 连接 / 25s 读取） |
