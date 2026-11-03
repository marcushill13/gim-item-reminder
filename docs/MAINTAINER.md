# Maintainer notes

The RuneLite client uses `https://gim-item-reminder.marcushill3313.workers.dev` by default. Players do not need to deploy anything.

The shared service runs as a Cloudflare Worker backed by D1; its source and schema are in `backend/`. Changes to Java code are released through the RuneLite Plugin Hub. Changes to `backend/src.js` require redeploying the Worker.

Keep group creator tokens and Discord webhook URLs out of Git. A D1 database ID in `wrangler.toml` is a resource identifier, not an API token. Check that `backend/wrangler.toml` points to the right D1 database before deploying, and do not reset migrations or create a new database for normal releases.

Quick backend test: `cd backend && npm test`. To deploy backend changes, authenticate with Wrangler and run `npx wrangler deploy` from `backend/`.

The group service currently accepts reports from anyone with the invite code. This is a reminder system, not a tamper-proof audit log. Rate limits and server monitoring are recommended before large-scale adoption.
