-- Keep the attachment snowflakes so a later PATCH doesn't accidentally remove images.
ALTER TABLE borrows ADD COLUMN discord_attachments TEXT NOT NULL DEFAULT '[]';
ALTER TABLE borrows ADD COLUMN show_quantity INTEGER NOT NULL DEFAULT 1;
