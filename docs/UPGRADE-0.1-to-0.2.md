# 从 0.1.0 升级到 0.2.0

0.1.0 → 0.2.0 是**破坏性变更**：身份表结构改了，模型配置从环境变量搬进数据库。
两种做法，按你的数据价值选。

## 方案 A：没有真实数据 → 直接重建（推荐）

0.1.0 刚发布，多数情况只是试跑。

```bash
docker compose down -v          # 删掉 pgdata 卷
docker compose up -d --build
```

Flyway 会用 `V1__init.sql` 重建全部表，分类种子自动写入。

## 方案 B：已经记了账 → 搬迁

### 1. 导出旧数据

```bash
docker exec ledger-pg psql -U ledger -c "\copy (
  SELECT type, amount, category, merchant, note, occurred_at
  FROM transactions WHERE deleted = false ORDER BY occurred_at
) TO STDOUT WITH CSV HEADER" > /tmp/tx.csv
```

### 2. 重建库

```bash
docker compose down -v
docker compose up -d --build
```

### 3. 建账号

打开 `/login.html` 注册（第一个用户自动成为管理员），记住 `users.id`（通常是 1）。

### 4. 导回账目

```bash
docker cp /tmp/tx.csv ledger-pg:/tmp/tx.csv
docker exec ledger-pg psql -U ledger -c "
CREATE TEMP TABLE tmp(
  type text, amount bigint, category text, merchant text, note text, occurred_at timestamptz
);
\copy tmp FROM '/tmp/tx.csv' WITH CSV HEADER
INSERT INTO transactions(user_id, type, amount, category, merchant, note, occurred_at, source)
SELECT 1, type, amount, category, merchant, note, occurred_at, 'migrate' FROM tmp;
"
```

### 5. 重配模型

`/admin.html` → 模型 → 重新录入 Base URL / 模型名 / API Key。
旧的 `LLM_*` 环境变量在 0.2.0 已不再读取。
