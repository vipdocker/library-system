-- 图书馆管理系统首版 schema（design-v0.1 第 6 节，完整覆盖）
-- 约定：主键 INTEGER PRIMARY KEY AUTOINCREMENT；*_at 为 INTEGER epoch 毫秒（UTC）；
-- *_date 为 TEXT YYYY-MM-DD（lib.timezone 业务日期）；布尔 INTEGER 0/1 + CHECK；
-- 枚举 TEXT + CHECK；外键默认 ON DELETE RESTRICT。
-- 本文件发布后禁止修改；后续变更只能新增 V{n}__*.sql。

CREATE TABLE app_user (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  username      TEXT    NOT NULL COLLATE NOCASE UNIQUE,
  password_hash TEXT    NOT NULL,
  role          TEXT    NOT NULL CHECK (role IN ('ADMIN','READER')),
  enabled       INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1)),
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);

CREATE TABLE reader (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL UNIQUE REFERENCES app_user(id),
  card_no    TEXT    NOT NULL UNIQUE,
  name       TEXT    NOT NULL,
  phone      TEXT,
  email      TEXT,
  status     TEXT    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX ix_reader_name ON reader(name);

CREATE TABLE category (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT NOT NULL COLLATE NOCASE UNIQUE,
  description TEXT,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);

CREATE TABLE book (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  isbn         TEXT NOT NULL UNIQUE,
  title        TEXT NOT NULL,
  author       TEXT NOT NULL,
  publisher    TEXT,
  publish_year INTEGER CHECK (publish_year IS NULL OR publish_year BETWEEN 1000 AND 9999),
  category_id  INTEGER REFERENCES category(id),
  description  TEXT,
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);
CREATE INDEX ix_book_title    ON book(title COLLATE NOCASE);
CREATE INDEX ix_book_author   ON book(author COLLATE NOCASE);
CREATE INDEX ix_book_category ON book(category_id);

CREATE TABLE book_copy (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id    INTEGER NOT NULL REFERENCES book(id),
  barcode    TEXT    NOT NULL UNIQUE,
  status     TEXT    NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','BORROWED','UNAVAILABLE')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX ix_copy_book_status ON book_copy(book_id, status);

CREATE TABLE loan (
  id                 INTEGER PRIMARY KEY AUTOINCREMENT,
  copy_id            INTEGER NOT NULL REFERENCES book_copy(id),
  reader_id          INTEGER NOT NULL REFERENCES reader(id),
  borrowed_at        INTEGER NOT NULL,
  borrow_date        TEXT    NOT NULL,
  due_date           TEXT    NOT NULL,
  returned_at        INTEGER,
  return_date        TEXT,
  renew_count        INTEGER NOT NULL DEFAULT 0 CHECK (renew_count >= 0),
  borrow_operator_id INTEGER NOT NULL REFERENCES app_user(id),
  return_operator_id INTEGER REFERENCES app_user(id),
  last_renew_operator_id INTEGER REFERENCES app_user(id),
  CHECK ((returned_at IS NULL) = (return_date IS NULL)),
  CHECK (due_date >= borrow_date)
);
CREATE UNIQUE INDEX ux_loan_active_copy ON loan(copy_id) WHERE returned_at IS NULL;
CREATE INDEX ix_loan_reader_active ON loan(reader_id, returned_at);
CREATE INDEX ix_loan_due_active    ON loan(due_date) WHERE returned_at IS NULL;
CREATE INDEX ix_loan_borrow_date   ON loan(borrow_date);
CREATE INDEX ix_loan_return_date   ON loan(return_date);