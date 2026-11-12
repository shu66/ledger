-- MySQL 8.0+ / utf8mb4

CREATE TABLE users (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  email         VARCHAR(190) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  nickname      VARCHAR(64)  NULL,
  role          VARCHAR(16)  NOT NULL DEFAULT 'user',
  status        VARCHAR(16)  NOT NULL DEFAULT 'active',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_login_at DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_email (email),
  CONSTRAINT ck_user_role   CHECK (role IN ('user','admin')),
  CONSTRAINT ck_user_status CHECK (status IN ('active','disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_identities (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  user_id     BIGINT       NOT NULL,
  provider    VARCHAR(16)  NOT NULL,
  external_id VARCHAR(128) NOT NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_identity (provider, external_id),
  KEY idx_ident_user (user_id),
  CONSTRAINT fk_ident_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE auth_tokens (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  user_id    BIGINT      NOT NULL,
  purpose    VARCHAR(24) NOT NULL,
  token_hash VARCHAR(64) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  used_at    DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_token_hash (token_hash),
  KEY idx_token_user (user_id),
  CONSTRAINT fk_token_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE categories (
  id   VARCHAR(32) NOT NULL,
  name VARCHAR(32) NOT NULL,
  type VARCHAR(8)  NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT ck_cat_type CHECK (type IN ('expense','income'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE transactions (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  user_id     BIGINT      NOT NULL,
  type        VARCHAR(8)  NOT NULL,
  amount      BIGINT      NOT NULL,
  category    VARCHAR(32) NOT NULL,
  merchant    VARCHAR(128) NULL,
  note        TEXT         NULL,
  occurred_at DATETIME(3) NOT NULL,
  source      VARCHAR(16) NOT NULL DEFAULT 'text',
  deleted     TINYINT(1)  NOT NULL DEFAULT 0,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_tx (user_id, occurred_at DESC),
  CONSTRAINT ck_tx_type   CHECK (type IN ('expense','income')),
  CONSTRAINT ck_tx_amount CHECK (amount > 0),
  CONSTRAINT fk_tx_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE merchant_rules (
  user_id  BIGINT      NOT NULL,
  keyword  VARCHAR(64) NOT NULL,
  category VARCHAR(32) NOT NULL,
  hits     INT         NOT NULL DEFAULT 1,
  PRIMARY KEY (user_id, keyword)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE parse_feedback (
  id           BIGINT     NOT NULL AUTO_INCREMENT,
  user_id      BIGINT     NULL,
  input_text   TEXT       NULL,
  model_output TEXT       NULL,
  final_record TEXT       NULL,
  edited       TINYINT(1) NOT NULL DEFAULT 0,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_feedback_user (user_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE llm_configs (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  name           VARCHAR(64)  NOT NULL,
  base_url       VARCHAR(255) NOT NULL,
  model          VARCHAR(64)  NOT NULL,
  api_key_cipher TEXT         NOT NULL,
  api_key_hint   VARCHAR(16)  NULL,
  enabled        TINYINT(1)   NOT NULL DEFAULT 1,
  is_default     TINYINT(1)   NOT NULL DEFAULT 0,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  -- MySQL 没有部分索引，用生成列 + 唯一索引做到「最多一个默认配置」
  default_uniq   TINYINT GENERATED ALWAYS AS (CASE WHEN is_default = 1 THEN 1 ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_one_default (default_uniq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE audit_logs (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  actor_id   BIGINT      NULL,
  action     VARCHAR(48) NOT NULL,
  target     VARCHAR(64) NULL,
  detail     TEXT        NULL,
  ip         VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_audit_time (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO categories (id, name, type) VALUES
  ('food','餐饮','expense'), ('transport','交通','expense'),
  ('shopping','购物','expense'), ('housing','居住','expense'),
  ('fun','娱乐','expense'), ('health','医疗','expense'),
  ('social','人情','expense'), ('other_exp','其他支出','expense'),
  ('salary','工资','income'), ('bonus_red','红包收入','income'),
  ('refund','退款','income'), ('reimburse','报销','income'),
  ('other_inc','其他收入','income');
