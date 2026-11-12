# 部署手册 v0.4.0（MySQL）

## 1. 本地

```bash
cd server
cp .env.example .env
openssl rand -hex 32   # JWT_SECRET
openssl rand -hex 32   # APP_SECRET

docker compose up -d mysql
docker compose logs -f mysql      # 等到 healthy
set -a && source .env && set +a
mvn spring-boot:run
```

`http://localhost:3000/health` 返回 `{"ok":true,"version":"0.4.0","db":"mysql"}`。

注册第一个账号 → 自动成为管理员 → `/admin.html` 配模型 → 点「测试」通过。

## 2. 服务器（Docker）

```bash
scp -r ledger-v0.4.0 user@host:/opt/
cd /opt/ledger-v0.4.0/server
cp .env.example .env && vim .env
docker compose up -d --build
```

生产环境把 3000 收回本机：

```yaml
ports:
  - "127.0.0.1:3000:3000"
```

## 3. Nginx + HTTPS

```nginx
server {
    listen 443 ssl http2;
    server_name ledger.example.com;
    ssl_certificate     /etc/letsencrypt/live/ledger.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/ledger.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }
}
```

`APP_PUBLIC_URL` 改成 `https://ledger.example.com`，否则重置密码链接是错的。
同时在 `.env` 里加 `APP_TRUST_PROXY=true`，否则所有请求的客户端 IP 都是
`127.0.0.1`，限流会误伤、审计日志也记不到真人。

## 4. 不用 Docker

```bash
mvn -DskipTests package
set -a && source .env && set +a      # 至少要 SPRING_DATASOURCE_PASSWORD / JWT_SECRET / APP_SECRET
java -jar target/ledger.jar
```

口令没有默认值，缺 `SPRING_DATASOURCE_PASSWORD` 会直接启动失败（这是有意的）。

需要本机有 MySQL 8 和 `ledger` 库：

```sql
CREATE DATABASE ledger CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'ledger'@'%' IDENTIFIED BY 'ledger';
GRANT ALL ON ledger.* TO 'ledger'@'%';
```

## 5. 备份

```bash
# 备份
docker exec ledger-mysql mysqldump -uledger -pledger --single-transaction \
  --routines --triggers ledger | gzip > ~/ledger-$(date +%F).sql.gz

# 每日 3 点，保留 30 天
0 3 * * * docker exec ledger-mysql mysqldump -uledger -pledger --single-transaction ledger | gzip > /opt/backup/ledger-$(date +\%F).sql.gz && find /opt/backup -name "*.sql.gz" -mtime +30 -delete

# 恢复
gunzip -c ledger-2026-11-12.sql.gz | docker exec -i ledger-mysql mysql -uledger -pledger ledger
```

`--single-transaction` 保证 InnoDB 表备份一致性，不会锁表。

**`APP_SECRET` 单独存一份。** 丢了以后 `llm_configs.api_key_cipher` 全部解不开。

## 6. 排错

| 现象 | 原因 | 处理 |
|---|---|---|
| 应用启动即退出，报 `Connection refused` | MySQL 没起来 | `docker compose ps` 看 healthcheck |
| `Public Key Retrieval is not allowed` | 连接串缺参数 | 加 `allowPublicKeyRetrieval=true&useSSL=false` |
| 注册后进不去后台 | 不是第一个用户 | `UPDATE users SET role='admin' WHERE email='...'` |
| 记账报 `NO_LLM_CONFIG` | 没配模型 | 后台添加并测试 |
| 账单时间差 8 小时 | 连接时区参数丢了 | 检查 `connectionTimeZone=%2B08:00` |
| 启动报 `Unknown or incorrect time zone: 'Asia/Shanghai'` | MySQL 没导入时区表 | 连接串改用 `connectionTimeZone=%2B08:00` |
| 中文变问号 | 库不是 utf8mb4 | 重建库，见第 4 节 SQL |
| Flyway 报 `Unsupported Database` | 缺 `flyway-mysql` | 检查 pom |

## 7. 升级

```bash
docker compose up -d --build
```

新增迁移放 `src/main/resources/db/migration/V3__xxx.sql`（V2 已被 `token_version` 占用），
Flyway 启动时按版本号自动应用。
**MySQL 的 DDL 不支持事务回滚**，写迁移脚本前先 `mysqldump`。
