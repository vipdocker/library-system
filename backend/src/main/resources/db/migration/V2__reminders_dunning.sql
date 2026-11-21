-- Reminder increment v1.1. Historical V1 remains unchanged.
CREATE TABLE notification (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    reader_id INTEGER NOT NULL REFERENCES reader(id),
    loan_id INTEGER NOT NULL REFERENCES loan(id),
    type TEXT NOT NULL CHECK (type IN ('DUE_SOON','OVERDUE','DUNNING')),
    due_date TEXT NOT NULL,
    seq INTEGER,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    read_at INTEGER,
    CHECK ((type = 'DUE_SOON' AND seq = 0) OR
           (type = 'OVERDUE' AND seq >= 1) OR
           (type = 'DUNNING' AND seq IS NULL)),
    CHECK (type = 'DUNNING' OR seq IS NOT NULL)
);
CREATE UNIQUE INDEX ux_notification_auto
    ON notification(loan_id, type, due_date, seq)
    WHERE type IN ('DUE_SOON','OVERDUE');
CREATE INDEX ix_notification_reader_time ON notification(reader_id, created_at DESC, id DESC);
CREATE INDEX ix_notification_reader_unread ON notification(reader_id) WHERE read_at IS NULL;

-- Insert record first, insert notification second, then associate in the same transaction.
-- Nullable notification_id avoids a mandatory cyclic relationship. No backlink is needed.
CREATE TABLE dunning_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    loan_id INTEGER NOT NULL REFERENCES loan(id),
    method TEXT NOT NULL CHECK (method IN ('PHONE','IN_PERSON','IN_APP','OTHER')),
    note TEXT CHECK (note IS NULL OR length(note) <= 500),
    operator_id INTEGER NOT NULL REFERENCES app_user(id),
    created_at INTEGER NOT NULL,
    notification_id INTEGER UNIQUE REFERENCES notification(id)
);
CREATE INDEX ix_dunning_loan_time ON dunning_record(loan_id, created_at, id);
