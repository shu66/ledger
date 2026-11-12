-- AI 调用配额：按用户 + 自然日 + 类型累加，防止解析/总结被刷
CREATE TABLE IF NOT EXISTS ai_usage (
  user_id BIGINT      NOT NULL,
  day     DATE        NOT NULL,
  kind    VARCHAR(16) NOT NULL,
  n       INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, day, kind),
  CONSTRAINT fk_ai_usage_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
