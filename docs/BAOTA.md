# 宝塔面板部署（Java 项目）

适用：一台 Linux 云服务器（2 核 2G 起），已安装宝塔面板 9.x，有域名更佳。
本项目是「Spring Boot jar + MySQL」，不需要 Tomcat，宝塔里用 **Java 项目** 托管即可。

---

## 0. 先确认

- 云服务器安全组放行：`22`、`80`、`443`
- **不要**放行 `3000`（后端只给 Nginx 转发）
- 域名建议提前解析到服务器 IP（微信小程序上线必须是 https 域名）

---

## 1. 装软件（宝塔 → 软件商店）

| 软件 | 版本 | 说明 |
|---|---|---|
| Nginx | 1.24+ | 反向代理 + HTTPS |
| MySQL | 8.0 | 字符集 utf8mb4 |
| Java 项目管理器 | 最新 | 宝塔「网站 → Java 项目」依赖它 |
| JDK | 17 | 项目是 Java 17，别用 8 |

装完确认 JDK 路径，一般是 `/www/server/java/jdk-17`（在 Java 项目管理器的「JDK 管理」里能看到）。

---

## 2. 建数据库

宝塔 → 数据库 → 添加数据库：

- 数据库名：`ledger`
- 用户名：`ledger`
- 密码：强密码（记下来）
- 字符集：`utf8mb4`

**不需要导入 SQL**。后端启动时 Flyway 会自动建表、写入 13 个系统分类，并按顺序执行 V1–V5 迁移。

---

## 3. 打包并上传 jar

本机（项目根目录）：

```bash
cd server
mvn -DskipTests clean package
```

产物：`server/target/ledger.jar`（约 24MB，已内含前端页面）。

上传到服务器：

```bash
scp server/target/ledger.jar root@你的服务器IP:/www/wwwroot/ledger/ledger.jar
```

或在宝塔 → 文件 → `/www/wwwroot/ledger/` → 上传。

目录最后是这样：

```
/www/wwwroot/ledger/
├── ledger.jar
└── .env            # 下一步创建
```

---

## 4. 写环境变量

在 jar 同目录新建 `.env`（宝塔文件管理里右键新建文件，权限设 600）：

```ini
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/ledger?useUnicode=true&characterEncoding=utf8&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false
SPRING_DATASOURCE_USERNAME=ledger
SPRING_DATASOURCE_PASSWORD=第2步的密码

JWT_SECRET=把 openssl rand -hex 32 的输出贴这里
APP_SECRET=把 openssl rand -hex 32 的输出贴这里
APP_PUBLIC_URL=https://你的域名
APP_ADMIN_EMAIL=你的邮箱
APP_TRUST_PROXY=true

AI_PARSE_DAILY=50
AI_SUMMARY_DAILY=20
TZ=Asia/Shanghai
```

生成密钥（服务器上执行）：

```bash
openssl rand -hex 32
```

几个必须注意的点：

- `APP_SECRET` 至少 32 字符，用来加密后台保存的 AI Key。**丢了就解不开已存的 Key**，只能重新填，务必备份。
- 时区必须写 `connectionTimeZone=%2B08:00`。MySQL 官方镜像/默认安装没有时区表，写 `Asia/Shanghai` 会直接启动失败。
- 走 Nginx 反代后要 `APP_TRUST_PROXY=true`，否则限流和审计日志拿到的都是 `127.0.0.1`。

> 也可以在 Java 项目的「环境变量」里逐条填，效果一样；`.env` 的好处是升级时不用重复填。

---

## 5. 建 Java 项目

宝塔 → 网站 → Java 项目 → 添加 Java 项目：

| 项 | 填什么 |
|---|---|
| 项目名称 | `ledger` |
| 项目路径 | `/www/wwwroot/ledger` |
| 项目端口 | `3000` |
| JDK 版本 | `17` |
| 启动方式 | jar 包 |
| jar 路径 | `/www/wwwroot/ledger/ledger.jar` |
| JVM 参数 | `-Xms256m -Xmx512m -Duser.timezone=Asia/Shanghai` |
| 开机自启 | 打开 |

保存 → 启动，看项目日志，正常会依次出现：

```
Flyway ... Migrating schema `ledger` to version "1 - init"
...
Successfully applied 5 migrations ... now at version v5
Tomcat started on port 3000 (http)
```

本机验证：

```bash
curl http://127.0.0.1:3000/health
# {"ok":true,"version":"0.5.0","db":"mysql"}
```

> 日志里出现 `APP_SECRET 没配置或太短`，说明 `.env` 没被读到。确认它在 **jar 同目录**，或改用 Java 项目的环境变量。

---

## 6. 域名 + 反向代理 + HTTPS

1. 宝塔 → 网站 → 添加站点：域名填 `ledger.example.com`，PHP 版本选「纯静态」，根目录随便建一个空目录。
2. 站点设置 → 反向代理 → 添加：
   - 代理名称：`ledger`
   - 目标 URL：`http://127.0.0.1:3000`
   - 发送域名：`$host`
   - 缓存：关闭
3. 站点设置 → SSL → Let's Encrypt → 申请证书 → 打开「强制 HTTPS」。

宝塔生成的 Nginx 配置大致是：

```nginx
location / {
    proxy_pass http://127.0.0.1:3000;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 60s;
}
```

访问 `https://你的域名` 应该能看到落地页，`/login.html` 能注册登录。

---

## 7. 微信小程序指向线上

1. `miniprogram/utils/config.js` 把 `PROD` 改成你的域名：

```js
const PROD = 'https://ledger.example.com';
```

   小程序在「体验版/正式版」会自动用 `PROD`，开发版仍用 `DEV`（本机 127.0.0.1）。

2. 微信公众平台 → 开发管理 → 开发设置 → 服务器域名 → **request 合法域名** 添加：

```
https://ledger.example.com
```

   必须是 https、已备案、不能带端口和路径。

3. 上传代码 → 提交审核 → 发布。

> 开发者工具里勾「不校验合法域名」只对本地开发有效，体验版/正式版不会生效。

---

## 8. 首次使用

1. 打开 `https://你的域名/login.html` → 注册第一个账号（自动成为管理员；或用 `APP_ADMIN_EMAIL` 指定邮箱）。
2. 进 `/admin.html` → AI 模型 → 添加：
   - 名称：`deepseek`
   - 地址：`https://api.deepseek.com`
   - 模型：`deepseek-flash` 或 `deepseek-chat`
   - Key：`sk-...`
3. 点「测试」，出现「连接正常 · xxx ms · 回复「正常」」即通。
4. 回到 `/app.html` 记一笔试试。

---

## 9. 升级版本

```bash
# 本机
cd server && mvn -DskipTests clean package
scp target/ledger.jar root@服务器IP:/www/wwwroot/ledger/ledger.jar.new
```

服务器上：

```bash
cd /www/wwwroot/ledger
mv ledger.jar ledger.jar.bak
mv ledger.jar.new ledger.jar
```

宝塔 → Java 项目 → `ledger` → 重启。

- Flyway 会自动执行新增的迁移，**不要手工改表结构**。
- 只改前端页面也建议重打包，因为 `static/` 在 jar 里。
- 回滚：把 `ledger.jar.bak` 换回来重启；如果新版本已执行过迁移，注意旧代码是否兼容。

---

## 10. 备份

宝塔 → 计划任务 → 备份数据库：选 `ledger`，每天凌晨 3 点，保留 7 份。

手工备份：

```bash
mysqldump -u ledger -p ledger | gzip > /www/backup/ledger-$(date +%F).sql.gz
```

另外把 `.env`（含 `JWT_SECRET`、`APP_SECRET`）单独存一份到密码管理器。

---

## 11. 排错表

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动报 `APP_SECRET 没配置或太短` | 环境变量没读到 | `.env` 放 jar 同目录，或用 Java 项目环境变量 |
| `Unknown or incorrect time zone: 'Asia/Shanghai'` | MySQL 没时区表 | URL 改 `connectionTimeZone=%2B08:00` |
| 502 Bad Gateway | 后端没起来 / 端口不对 | 看 Java 项目日志，`curl 127.0.0.1:3000/health` |
| 打开域名是宝塔默认页 | 反代没生效 | 检查站点反代目标和「发送域名」 |
| 小程序 `request:fail url not in domain list` | 没配合法域名 | 公众平台加 https 域名 |
| 登录 401 `BAD_CREDENTIALS` | 密码错 / 账号被停用 | 后台重置密码或启用账号 |
| AI 测试 502 `LLM_UPSTREAM_401` | Key 无效 | 重新填 Key |
| AI 解析 429 `AI_QUOTA_EXCEEDED` | 当日额度用完 | 调大 `AI_PARSE_DAILY` 或等第二天 |
| 时间差 8 小时 | 时区 | `TZ=Asia/Shanghai` + URL 固定偏移 |
| 审计日志 IP 全是 127.0.0.1 | 没开 trust-proxy | `APP_TRUST_PROXY=true` |
| 改完代码页面没变 | jar 没换/没重启 | 重新打包上传并重启 |

---

## 12. 资源与安全

- 2 核 2G 足够：JVM 512m + MySQL 400m + Nginx 几十 M。
- `3000` 只监听本机，云安全组和宝塔防火墙都别放行。
- `.env` 权限 600，已加入 `.gitignore`，不要提交到仓库。
- 想用 Docker 的话，仓库里也有 `docker-compose.yml`，见 `docs/DEPLOY.md`。
