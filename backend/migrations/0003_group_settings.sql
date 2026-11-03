-- The creator token is hashed; the Discord webhook stays server-side and never appears in group state.
ALTER TABLE groups ADD COLUMN creator_token_hash TEXT;
ALTER TABLE groups ADD COLUMN discord_webhook_url TEXT;
ALTER TABLE borrows ADD COLUMN discord_webhook_url TEXT;
