-- ============================================================
--  考研复习助手 · 建库脚本
--
--  执行：
--    mysql -uroot -proot --default-character-set=utf8mb4 -e "source schema.sql"
--  （要在 backend 目录下执行，且命令行不要带中文路径）
-- ============================================================

CREATE DATABASE IF NOT EXISTS ai_kb
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai_kb;

DROP TABLE IF EXISTS chat_msg;
DROP TABLE IF EXISTS chat_session;
DROP TABLE IF EXISTS kb_chunk;
DROP TABLE IF EXISTS kb_doc;
DROP TABLE IF EXISTS subject;

-- ------------------------------------------------------------
-- 科目（两级：分组 → 具体科目）
--   公共课 ─ 英语 ─ 英语一 / 英语二
--          ─ 数学 ─ 数学一 / 数学二 / 线性代数 / 概率论
--          ─ 政治 ─ 政治
--   专业课 ─ 408 ─ 数据结构 / 操作系统
--
--   为什么用一张表 + parent_code 自关联，而不是建两张表？
--     科目以后可能加三级（比如"数学一 → 高数 → 第 3 章"），
--     自关联只要加数据，不用改表结构。
-- ------------------------------------------------------------
CREATE TABLE subject (
    code        VARCHAR(32) PRIMARY KEY COMMENT '科目编码，如 english1',
    name        VARCHAR(50) NOT NULL COMMENT '显示名，如 英语一',
    category    VARCHAR(32) NOT NULL COMMENT '分类：公共课 / 专业课',
    parent_code VARCHAR(32) DEFAULT NULL COMMENT '父级编码，顶级为 NULL',
    sort        INT DEFAULT 0 COMMENT '排序',
    intro       VARCHAR(200) DEFAULT NULL COMMENT '一句话说明',
    KEY idx_parent (parent_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='科目树';

-- ------------------------------------------------------------
-- 知识库文档（按科目归属）
-- ------------------------------------------------------------
CREATE TABLE kb_doc (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    subject_code VARCHAR(32)  NOT NULL COMMENT '所属科目',
    title        VARCHAR(200) NOT NULL,
    source       VARCHAR(255) DEFAULT NULL,
    char_count   INT DEFAULT 0,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_subject (subject_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档';

-- ------------------------------------------------------------
-- 文档切片（检索的最小单位）
--
--   ★ content 上的全文索引必须带 WITH PARSER ngram
--     否则中文按整段当一个词，搜「死锁」命中不了。
-- ------------------------------------------------------------
CREATE TABLE kb_chunk (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    doc_id       INT NOT NULL,
    subject_code VARCHAR(32) NOT NULL,
    seq          INT NOT NULL,
    content      TEXT NOT NULL,
    KEY idx_doc (doc_id),
    KEY idx_subject (subject_code),
    FULLTEXT KEY ft_content (content) WITH PARSER ngram,
    CONSTRAINT fk_chunk_doc FOREIGN KEY (doc_id) REFERENCES kb_doc(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档切片';

-- ------------------------------------------------------------
-- 会话（带科目，复习时按科目分开记）
-- ------------------------------------------------------------
CREATE TABLE chat_session (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    subject_code VARCHAR(32) NOT NULL,
    title        VARCHAR(100) NOT NULL DEFAULT '新对话',
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_subject (subject_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话会话';

CREATE TABLE chat_msg (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    session_id INT NOT NULL,
    role       VARCHAR(16) NOT NULL COMMENT 'user / assistant',
    content    TEXT NOT NULL,
    citations  TEXT COMMENT '引用片段 JSON',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_session (session_id),
    CONSTRAINT fk_msg_session FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息';

-- ============================================================
--  科目数据（三级：公共课/专业课 → 模块 → 具体科目）
--    公共课 ─ 英语 ─ 英语一 / 英语二
--           ─ 数学 ─ 数学一 / 数学二 / 线性代数 / 概率论
--           ─ 政治
--    专业课 ─ 408 ─ 数据结构 / 操作系统
-- ============================================================
INSERT INTO subject(code, name, category, parent_code, sort, intro) VALUES
('public',   '公共课',              '公共课', NULL,      10, '政治 / 英语 / 数学'),
('english',  '英语',                '公共课', 'public',  11, ''),
('english1', '英语一',              '公共课', 'english', 12, '学硕常用'),
('english2', '英语二',              '公共课', 'english', 13, '专硕常用'),
('math',     '数学',                '公共课', 'public',  20, ''),
('math1',    '数学一',              '公共课', 'math',    21, '高数 + 线代 + 概率'),
('math2',    '数学二',              '公共课', 'math',    22, '高数 + 线代，不考概率'),
('linear',   '线性代数',            '公共课', 'math',    23, '单独强化'),
('prob',     '概率论与数理统计',    '公共课', 'math',    24, '数一 / 数三考'),
('politics', '政治',                '公共课', 'public',  30, '马原 / 毛中特 / 史纲 / 思修'),
('pro',      '专业课',              '专业课', NULL,      40, ''),
('cs408',    '408 专业课',          '专业课', 'pro',     41, '计算机学科专业基础'),
('ds',       '数据结构',            '专业课', 'cs408',   42, ''),
('os',       '操作系统',            '专业课', 'cs408',   43, '');

SELECT CONCAT('科目数=', COUNT(*)) AS result FROM subject;
SELECT CONCAT('表数量=', COUNT(*)) AS result FROM information_schema.tables WHERE table_schema='ai_kb';
