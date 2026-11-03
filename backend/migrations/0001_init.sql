CREATE TABLE IF NOT EXISTS groups (
 code_hash TEXT PRIMARY KEY,
 name TEXT NOT NULL,
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS tracked (
 code_hash TEXT NOT NULL,
 item_id INTEGER NOT NULL,
 item_name TEXT NOT NULL,
 PRIMARY KEY (code_hash, item_id)
);
CREATE TABLE IF NOT EXISTS borrows (
 id TEXT PRIMARY KEY,
 code_hash TEXT NOT NULL,
 item_id INTEGER NOT NULL,
 item_name TEXT NOT NULL,
 borrower TEXT NOT NULL,
 quantity INTEGER NOT NULL,
 initial_quantity INTEGER NOT NULL,
 status TEXT NOT NULL DEFAULT 'borrowed',
 discord_message_id TEXT,
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 returned_at TEXT
);
CREATE INDEX IF NOT EXISTS borrowed_index ON borrows(code_hash,item_id,status,created_at);
