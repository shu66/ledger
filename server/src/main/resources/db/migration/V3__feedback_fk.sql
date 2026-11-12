-- parse_feedback 当初建表时漏了外键，删账号后原始输入会一直留在库里。
-- 先清掉已经悬空的行，再补 ON DELETE CASCADE。

DELETE f FROM parse_feedback f
    LEFT JOIN users u ON u.id = f.user_id
WHERE f.user_id IS NOT NULL AND u.id IS NULL;

ALTER TABLE parse_feedback
    ADD CONSTRAINT fk_feedback_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
