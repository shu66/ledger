# CHANGELOG

## 0.5.3 — 2026-11-12

### 变更
- **登录页重做**：改用全屏自定义导航（顶部按状态栏高度留白），品牌区更紧凑，卡片圆角与阴影更柔和；「微信一键登录」改为浅绿描边按钮，与暖纸底色更协调；输入框聚焦时橙色描边 + 白底，并补了字段提示文案。
- **输入框一次点中**：整块输入区（标签、留白、输入框）都可以点击聚焦，输入框加 `focus` 受控状态与跟随 blur 的清理逻辑，解决「密码框有时候要点两次才能输入」。

## 0.5.2 — 2026-11-12

### 修复
- **微信登录 500 INTERNAL**：微信 `jscode2session` 返回的是 `Content-Type: text/plain`，旧代码让 Spring 直接映射成对象，抛「不支持的媒体类型」，被兜底处理成 500。现在先取字符串再用 Jackson 解析；微信返回错误时把 `errcode/errmsg` 原样带回（如 `40125 invalid appsecret`），连不上微信则返回 502 `WX_UPSTREAM`。

## 0.5.1 — 2026-11-12

### 修复
- **小程序登录页**：输入框改为固定 92rpx 高度（之前只有 padding，部分机型上文字被压到看不见）；
  去掉页面的 `overflow: hidden`，小屏和弹出键盘时整页可以滚动，不再把登录按钮挤出屏幕
- **登录页光斑**：装饰用的两个径向渐变球放进固定定位的裁切层，不再把页面撑出横向滚动
- **微信登录入口回来了**：登录页新增「微信一键登录」按钮。此前只有冷启动时自动登录一次，
  退出登录或点过「先不绑定」之后就再也找不到微信入口；现在随时可以点，未绑定的微信会转到邮箱绑定
- **退出登录**：主动退出后记 `skipWx`，下次启动不会又自动用微信登进去（想进就点按钮）
- **微信登录报错更清楚**：后台没配 `WX_APPID` / `WX_SECRET` 时返回 503 `WX_NOT_CONFIGURED` 并给出处理办法；微信返回错误时带上 `errcode/errmsg`（不再是笼统的 WX_LOGIN_FAILED）

### 文档
- `docs/BAOTA.md` 补微信登录配置（`WX_APPID` / `WX_SECRET`）与两条排错项

### 变更
- **tabBar 图标**：记一笔（铅笔）、手动记（圆形加号）、账单（票据）三组 PNG，
  选中态用陶土橙，不再是纯文字

## 0.5.0 — 2026-11-12

### 新增
- **小程序账单页「统计 · 分类」视图**：近 6 个月收支柱图、每日支出柱图、分类占比条形图；
  点分类行或汇总卡里的 Top 分类，可只看该类明细（`/api/transactions?category=`）
- **小程序解析后可改备注**：AI 解析出的每笔账，备注是输入框，确认前可随手改

### 文档
- 新增 `docs/BAOTA.md`：宝塔面板 Java 项目部署全流程（建库、jar、Java 项目、反向代理、
  HTTPS、小程序域名、升级、备份、排错表）

## 0.4.1 — 2026-11-12

### 新增
- 解析结果里可以直接新建分类：网页分类下拉最后一项「＋ 新建分类…」、小程序分类浮层右下角
  「＋ 新建分类」，建完自动选中当前这笔，不用退出解析结果（同名分类会自动复用）

### 修复
- 小程序开发地址默认改为 `http://127.0.0.1:3000`（开发者工具和后端在同一台电脑）。
  之前写死的 `192.168.1.10` 与本机实际网段（`192.168.10.x`）不同，会报
  `ERR_CONNECTION_TIMED_OUT`；真机调试再按注释改成电脑的内网 IP

## 0.4.0 — 2026-11-12

### 新增
- **修改账单** `PATCH /api/transactions/{id}`：金额、类型、分类、时间、备注、商家都能改；
  网页账单明细每行有 ✎，小程序点任意一笔弹出「修改金额 / 修改分类 / 删除」
- **账单多周期**：今天 / 本周 / 本月 / 今年 / 自定义起止日期；`/api/transactions` 与
  `/api/report` 都支持 `from` + `to`，统计、图表、导出会跟着区间走
- **账单搜索** `GET /api/transactions?q=`：同时匹配备注、商家、分类名（转义 `% _ \`）
- **手动补账**：网页「＋ 补一笔」直接加一条空白账目再填；小程序「手动记」页
- **AI 总结** `POST /api/ai/summary`：按区间输出 3–5 句中文复盘（结构、异常、建议），
  网页账单页「AI 总结这段」直接展示；`GET /api/ai/usage` 查看当天次数
- **AI 次数限制**：`V5__ai_usage.sql` 按天计数，解析默认 50 次/天、总结 20 次/天，
  超限返回 429 `AI_QUOTA_EXCEEDED`，可用 `AI_PARSE_DAILY` / `AI_SUMMARY_DAILY` 调整
- **离线补推**：后端连不上时账目先存浏览器 localStorage / 小程序 storage，
  恢复后自动（网页每 20 秒、小程序回前台）或手动点「待同步」一起推送

### 变更
- `/api/report` 返回 `from/to/days` 与「上一段」区间；长时间区间自动改用月度趋势/柱图
- `/api/transactions` 默认返回 500 条（`limit` 可到 2000），不再固定 100 条
- 导出支持 `from` + `to`（文件名 `ledger-<from>_<to>.csv`），仍兼容 `month` / `all`
- 小程序账单页不再依赖 `/api/summary`（只支持月），改为按区间在客户端汇总
- 解析超时仍为 45 秒；`app.ai-*` 配额可以在 `application.yml` / 环境变量里改

### 修复
- `spring.datasource.password` 恢复为 `${SPRING_DATASOURCE_PASSWORD:}`（不再带默认口令）
- 请求体 JSON 非法、缺参数、类型不对统一返回 400 `BAD_REQUEST`，不再落到 500
- 时间区间非法（`from > to`、超过 731 天）分别返回 `BAD_RANGE` / `RANGE_TOO_LONG`

## 0.3.1 — 2026-11-12

### 新增
- **统计接口** `GET /api/report?month=`：本月收支/笔数与环比、分类结构、每日收支、
  近 6 个月趋势、商家 Top 6（月份缺省取当月，非法月份 400 `BAD_MONTH`）
- **一键导出** `GET /api/transactions/export?month=`：CSV（UTF-8 BOM、中文表头、
  金额两位小数、逗号/引号/换行转义）；`month` 省略或传 `all` 导出全部
- **网页图表**：纯 SVG 的环形图、收支柱状图、每日柱状图、KPI 迷你走势，写在 `common.js`，
  账本页与落地页共用；账本页新增 KPI 环比、支出结构、近 6 个月收支、每日支出、
  商家排行与分类占比；账单页提供「导出本月 / 导出全部」
- 落地页改版：深色预览卡（环形图 + 趋势）与三张图表示例卡

### 变更
- 写账页「最近入账」改为通栏三列（窄屏两列 / 一列）
- 负结余的环比显示为「新增」而非负百分比；金额负数统一显示为 `-¥1,234.00`
- 图表月份标签改为「6月」式；环形图中心金额带千分位
- 侧栏邮箱过长时省略，不再折行

### 修复
- `/api/parse` 的 `createdCategories` 返回分类**名称**（此前文档写 id，前端提示也不一致）
- `ReportController` 漏了类级 `@RequestMapping("/api")` 导致 `/api/report` 404
- 导出后立刻 `revokeObjectURL` 会让部分浏览器取消下载，改为 10 秒后释放

## 0.3.0 — 2026-11-12

### 新增
- **自定义分类**：`categories` 增加 `user_id` / `icon` / `sort`（`V4__custom_categories.sql`），
  系统分类补上 emoji 与排序；新增 `GET /api/categories`、`POST /api/categories`、
  `DELETE /api/categories/{id}`（自建分类，同名复用，名字 ≤12 字）
- **AI 自主建分类**：解析时模型给出 `suggest` 且落到「其他支出/收入」的分类会自动落库，
  `/api/parse` 新增 `createdCategories` 返回新建的分类**名称**，客户端提示「AI 新建了分类」
- **手动记账**：`POST /api/transactions/batch` 新增 `source`（`text` / `manual`），
  手动账目不写 `parse_feedback`；小程序新增「手动记」tab，网页「记一笔」可在 AI / 手动间切换
- **网页端整体重做**：登录页、落地页、账本、后台共用一套设计系统（重写 `style.css` 与
  `common.js`）；账本改为侧边栏应用（记一笔 / 账单 / 分类），后台为四面板（概览 / 用户 /
  AI 模型 / 审计）；窄屏自动折成单列
- **小程序端整体重做**：暖纸色视觉、首页本月概览卡、解析结果卡（可改金额、换分类、删单笔）、
  手动记账页（4 列分类宫格 + 新建分类）、账单页（按月切换、分类占比、按天分组、长按删除）

### 变更
- 小程序 tabBar 改为三项：记一笔 / 手动记 / 账单
- `/api/categories` 每行新增 `icon`（emoji）与 `custom`（是否自建）
- 小程序解析请求超时从 20s 调到 45s

### 修复
- 后台「测试模型」永远 400 的问题（JSON 模式强制的 `response_format` 不再用于连通性测试）
- 漏挂的 `POST /api/parse` 路由（见 0.2.1 条目）

## 0.2.1 — 2026-11-12

### 变更
- 存储从 PostgreSQL 换到 MySQL 8（方言、驱动、Flyway 模块、docker-compose），
  接口契约不变，见 `docs/MYSQL.md`

### 修复
- `application.yml` 数据源变量名笔误 `SPRING_DATASOURCE_Ubc` → `SPRING_DATASOURCE_URL`，
  环境变量和 docker-compose 里的连接串现在真正生效
- 去掉写死的数据库默认口令；缺 `SPRING_DATASOURCE_PASSWORD` 时启动直接失败，不再静默连开发库
- `/health` 的版本号改读 `app.version`（构建时由 pom 版本填充），不再硬编码
- 访问令牌校验改为读库：账号停用、登出、改密后旧 JWT 立即失效，不必等 30 天
- 新增 `POST /auth/logout`，自增 `users.token_version` 吊销该账号全部令牌（`V2__token_version.sql`）
- 重置密码成功后同时吊销旧令牌
- 限流与审计日志支持 `X-Forwarded-For`（`APP_TRUST_PROXY=true` 时），限流桶会清理过期项
- `parse_feedback.model_output` 现在写入模型原始返回，`edited` 记录用户是否改过
- `parse_feedback` 补上 `ON DELETE CASCADE` 外键（`V3__feedback_fk.sql`），删账号不再遗留原始输入
- 小程序：点「先不绑定」不再在首页和登录页之间来回弹；正式版自动切到 `PROD` 域名；账单页加退出登录
- 补了第一批单元测试（`mvn test`，6 个用例）：限流窗口/上限、X-Forwarded-For 只在
  `APP_TRUST_PROXY=true` 时生效
- 启动容错：`.env` 同时从当前目录和 `./server/` 加载（IDE 里工作目录是项目根也能跑）；
  `APP_SECRET` / `JWT_SECRET` 缺失时给出可执行的报错，而不是 `Could not resolve placeholder`
- 后台「测试模型」修好了：之前把 `response_format=json_object` 也用在连通性测试上，
  DeepSeek 等要求 prompt 里必须出现 "json" 字样，于是永远 400；现在测试走普通模式。
  上游 4xx/超时现在返回 `502 LLM_UPSTREAM_<状态码>` / `504 LLM_TIMEOUT` 并带上游错误信息，
  不再是一句 500 INTERNAL
- 补回漏掉的 `POST /api/parse` 路由：0.2.0 包里只有 `ParseService`、没有 `ParseController`，
  小程序「记一笔」必然 500 INTERNAL。现在按 `docs/API.md` 的契约返回
  `{items, raw, model}`，空文本 400 `EMPTY_TEXT`、超 500 字 400 `TEXT_TOO_LONG`
- 未匹配的路由返回 404 `NOT_FOUND`（原来会被兜底成 500 INTERNAL，问题被掩盖）；
  小程序请求超时从 20s 提到 45s（模型解析可能超过 20s）

### 已知限制
- 仍未接 SMTP，重置密码链接打印在服务端日志
- 限流是单进程内存桶，多实例部署需换成 Redis 或网关限流

## 0.2.0 — 2026-11-12

### 破坏性变更
- `users` 表以 `email` 为根身份，`openid` 移入 `user_identities`
- 模型配置从环境变量迁移到数据库 `llm_configs`，需在后台重新录入
- 建表方式从 `schema.sql` 改为 Flyway 迁移

### 新增
- 邮箱注册 / 登录 / 忘记密码（重置链接输出到日志）
- 密码用 BCrypt（strength 10）哈希
- 微信绑定：未绑定时只发 10 分钟 `bindToken`，不直接发访问令牌
- 主动解绑微信 `POST /auth/wx/unbind`
- 网页版：`/login.html`、`/app.html`、`/admin.html`
- 管理后台：用户列表、改角色、停用、重置密码、审计日志
- 模型配置 CRUD + 连通性测试 + 默认配置 + 失败自动降级
- API Key 以 AES-256-GCM 加密存储，前端只见尾号
- 首用户自动成为管理员；`APP_ADMIN_EMAIL` 可指定
- 全部管理动作写 `audit_logs`

### 改进
- 管理员校验读库，权限变更立即生效
- 登录/注册/找回密码分别限流
- 登录失败统一返回 `BAD_CREDENTIALS`，不暴露邮箱是否存在

### 已知限制
- 未接 SMTP，重置密码链接打印在服务端日志
- 无邮箱验证（注册即可用）
- 小程序端仍未做手动记账降级入口

## 0.1.0 — 2026-11-12

首个可运行版本：一句话解析、金额校验、置信度、按月账单。
