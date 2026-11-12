-- 令牌版本：登出 / 改密时自增，签发时写进 JWT 的 ver claim。
-- 版本对不上即视为已吊销，不再等 30 天自然过期。
ALTER TABLE users
  ADD COLUMN token_version INT NOT NULL DEFAULT 0 AFTER status;
