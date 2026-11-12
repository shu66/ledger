# PostgreSQL → MySQL 移植说明

## 1. 语法对照

| 原来（PostgreSQL） | 现在（MySQL） | 备注 |
|---|---|---|
| `BIGSERIAL` | `BIGINT AUTO_INCREMENT` | 需配 `PRIMARY KEY` |
| `TIMESTAMPTZ` | `DATETIME(3)` | 无反时区，靠连接参数统一到上海 |
| `now()` | `NOW(3)` | 毫秒精度对齐 `DATETIME(3)` |
| `BOOLEAN` | `TINYINT(1)` | 查询里 `deleted = 0` |
| `INSERT ... RETURNING id` | `GeneratedKeyHolder` | MySQL 不支持 RETURNING |
| `?::text` / `?::boolean` | 直接 `?` | MySQL 由上下文推断类型 |
| `now() + interval '30 minutes'` | `DATE_ADD(NOW(3), INTERVAL 30 MINUTE)` | |
| `date_trunc('month', now())` | `DATE_SUB(CURDATE(), INTERVAL DAYOFMONTH(CURDATE())-1 DAY)` | |
| 部分唯一索引 `WHERE is_default` | 生成列 + 唯一索引 | 见下 |
| `df -h` 无所谓 | `ENGINE=InnoDB CHARSET=utf8mb4` | 中文与 emoji |

「最多一个默认模型」用生成列实现：

```sql
is_default   TINYINT(1) NOT NULL DEFAULT 0,
default_uniq TINYINT GENERATED ALWAYS AS (CASE WHEN is_default = 1 THEN 1 ELSE NULL END) STORED,
UNIQUE KEY uk_one_default (default_uniq)
```

多个 0 不会冲突（NULL 不参与唯一约束），但第二个 `is_default=1` 会被拒绝。
应用层仍然先 `UPDATE ... SET is_default = 0` 再设新的。

## 2. 时区

MySQL 的 `DATETIME` 不带时区，驱动默认按 JVM 时区解释。所以连接串必须写死：

```
connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true
```

注意用 `%2B08:00`（固定偏移），**不要**写 `Asia/Shanghai`：MySQL 官方 Docker 镜像和
多数本机安装都没导入时区表（`mysql.time_zone_name` 是空的），写时区名会直接连不上，
报 `Unknown or incorrect time zone: 'Asia/Shanghai'`。中国没有夏令时，固定偏移足够；
非要写名字就得先 `mysql_tzinfo_to_sql` 导入时区数据。

代码里统一用 `LocalDateTime` 读写，只在出入参时转 `OffsetDateTime`：

```java
// 写入
LocalDateTime at = OffsetDateTime.parse(iso).atZoneSameInstant(CN).toLocalDateTime();
// 读出
LocalDateTiime x = rs.getObject("occurred_at", LocalDateTime.class);
x.atZone(CN).toOffsetDateTime();
```

**不要**在 SQL 里用 `DATE_FORMAT(occurred_at, '%Y-%m')` 切月，会跟着会话时区跑偏。
月份过滤在 Java 里算好边界再 `BETWEEN`。

## 3. 索引与约束

- `KEY idx_tx (user_id, occurred_at DESC)`：MySQL 8 支持降序索引
- 外键用 `ON DELETE CASCADE`，删用户时账目自动清掉
- `CHECK` 约束在 8.0.16+ 真正生效，8.4 没问题；5.7 会被忽略

## 4. 从 PostgreSQL 搬数据

### 4.1 从旧库导出

```bash
docker exec ledger-pg psql -U ledger -c "\copy (
  SELECT user_id, type, amount, category, merchant, note,
         to_char(occurred_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI:SS.MS'),
         source
  FROM transactions WHERE deleted = false ORDER BY id
) TO STDOUT WITH CSV" > /tmp/tx.csv
```

### 4.2 建 MySQL 库并启动

```bash
docker compose up -d mysql
# 等 healthcheck 通过
docker compose logs -f mysql
```

应用首次启动会由 Flyway 建表。先启动一次应用，再导数据：

```bash
set -a && source .env && set +a
mvn spring-boot:run    # 看到 Started LedgerApplication 后 Ctrl+C
```

### 4.3 导入

```bash
docker cp /tmp/tx.csv ledger-mysql:/tmp/tx.csv
docker exec -i ledger-mysql mysql -uledger -pledger ledger -e "
LOAD DATA LOCAL INFILE '/tmp/tx.csv'
INTO TABLE transactions
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '\"'
LINES TERMINATED BY '\n'
(user_id, type, amount, category, merchant, note, occurred_at, source);
"
```

注意：`user_id` 要先在新库建好同名账号并对齐 id；或者导入后 `UPDATE` 一次。

### 4.4 模型 Key 必须重录

`APP_SECRET` 换了或库换了，旧的 `api_key_cipher` 都解不开。去后台重新填。

## 5. 常见报错

| 报错 | 原因 | 处理 |
|---|---|---|
| `Public Key Retrieval is not allowed` | 连接串缺参数 | 加 `allowPublicKeyRetrieval=true&useSSL=false` |
| `Unknown database 'ledger'` | 库没建 | `CREATE DATABASE ledger CHARACTER SET utf8mb4` |
| `Data too long for column 'email'` | 索引长度 | `email` 已设 190，够用 |
| `Incorrect string value: '\xF0\x9F...'` | 不是 utf8mb4 | 建库/建表都用 `utf8mb4` |
| `Cannot add foreign key constraint` | 引擎或类型不一致 | 全表 InnoDB + BIGINT 对齐 |
| `Specified key was too long` | 联合主键超长 | `merchant_rules` 已控制在 64 字符 |
| 时间差 8 小时 | 连接时区没设 | 检查 `connectionTimeZone` |
| Flyway 报 `Unsupported Database: MySQL` | 缺模块 | 加 `flyway-mysql` 依赖 |
