-- 分类支持用户/AI 自定义：系统分类 user_id 为 NULL，自定义分类挂在用户下
ALTER TABLE categories
    ADD COLUMN user_id BIGINT NULL AFTER id,
    ADD COLUMN icon VARCHAR(16) NULL AFTER type,
    ADD COLUMN sort INT NOT NULL DEFAULT 100 AFTER icon,
    ADD COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER sort,
    ADD CONSTRAINT fk_cat_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    ADD UNIQUE KEY uk_cat_user_name (user_id, name);

UPDATE categories SET icon = '🍜', sort = 10 WHERE id = 'food';
UPDATE categories SET icon = '🚕', sort = 20 WHERE id = 'transport';
UPDATE categories SET icon = '🛍️', sort = 30 WHERE id = 'shopping';
UPDATE categories SET icon = '🏠', sort = 40 WHERE id = 'housing';
UPDATE categories SET icon = '🎮', sort = 50 WHERE id = 'fun';
UPDATE categories SET icon = '💊', sort = 60 WHERE id = 'health';
UPDATE categories SET icon = '🎁', sort = 70 WHERE id = 'social';
UPDATE categories SET icon = '✨', sort = 90 WHERE id = 'other_exp';
UPDATE categories SET icon = '💰', sort = 10 WHERE id = 'salary';
UPDATE categories SET icon = '🧧', sort = 20 WHERE id = 'bonus_red';
UPDATE categories SET icon = '↩️', sort = 30 WHERE id = 'refund';
UPDATE categories SET icon = '🧾', sort = 40 WHERE id = 'reimburse';
UPDATE categories SET icon = '💵', sort = 90 WHERE id = 'other_inc';
