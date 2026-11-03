import assert from 'node:assert/strict';
import test from 'node:test';
import worker from '../src.js';

function fakeDatabase() {
  const groups = new Map();
  return {
    groups,
    prepare(sql) {
      return {
        bind(...values) {
          return {
            async first() {
              if (sql.includes('FROM groups')) {
                const row = groups.get(values[0]);
                if (!row) return null;
                return sql.includes('SELECT code_hash') ? { code_hash: values[0] } : { ...row };
              }
              return null;
            },
            async run() {
              if (sql.includes('INSERT INTO groups')) {
                if (groups.has(values[0])) throw Error('UNIQUE constraint failed');
                groups.set(values[0], { name: values[1], creator_token_hash: values[2], discord_webhook_url: null });
              }
              if (sql.includes('UPDATE groups SET discord_webhook_url=?')) {
                groups.get(values[1]).discord_webhook_url = values[0];
              }
              if (sql.includes('UPDATE groups SET discord_webhook_url=NULL')) {
                groups.get(values[0]).discord_webhook_url = null;
              }
              return { success: true };
            },
            async all() { return { results: [] }; }
          };
        }
      };
    }
  };
}

const call = (env, path, method = 'GET', body = undefined, headers = {}) => worker.fetch(
  new Request(`https://example.workers.dev/${path}`, {
    method,
    headers: { 'content-type': 'application/json', ...headers },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {})
  }), env
);

test('health identifies version 5', async () => {
  const res = await call({}, 'health');
  assert.deepEqual(await res.json(), { ok: true, service: 'gim-item-reminder', api_version: 5 });
});

test('server creates six-character code, join reads state and webhook is not exposed', async () => {
  const env = { DB: fakeDatabase() };
  const response = await call(env, 'groups', 'POST', { name: 'The Plankers' });
  assert.equal(response.status, 201);
  const { code, creatorToken } = await response.json();
  assert.match(code, /^[A-HJ-NP-Z2-9]{6}$/);
  assert.ok(creatorToken.length >= 40);
  const state = await call(env, `state?code=${code}`);
  assert.equal(state.status, 200);
  assert.deepEqual(await state.json(), {
    name: 'The Plankers', tracked: [], borrowed: [], discord_connected: false
  });
  const unauthorized = await call(env, 'discord', 'PUT', { code, webhookUrl: 'https://discord.com/api/webhooks/123/secret' });
  assert.equal(unauthorized.status, 403);
  const valid = await call(env, 'discord', 'PUT', { code, creatorToken, webhookUrl: 'https://discord.com/api/webhooks/123/secret' });
  assert.equal(valid.status, 200);
  assert.deepEqual(await valid.json(), { ok: true, discord_connected: true });
  const stateWithDiscord = await call(env, `state?code=${code}`);
  const body = await stateWithDiscord.json();
  assert.equal(body.discord_connected, true);
  assert.ok(!JSON.stringify(body).includes('secret'));
  assert.ok(!JSON.stringify(body).includes(creatorToken));
  const removed = await call(env, 'discord', 'DELETE', { code, creatorToken });
  assert.equal(removed.status, 200);
  assert.equal((await removed.json()).discord_connected, false);
});

test('bad names and invalid webhook hosts are rejected', async () => {
  const env = { DB: fakeDatabase() };
  const bad = await call(env, 'groups', 'POST', { name: ' ' });
  assert.equal(bad.status, 400);
  const created = await (await call(env, 'groups', 'POST', { name: 'GIM' })).json();
  const attempt = await call(env, 'discord', 'PUT', {
    code: created.code, creatorToken: created.creatorToken,
    webhookUrl: 'https://attacker.example/api/webhooks/123/token'
  });
  assert.equal(attempt.status, 400);
  assert.match((await attempt.json()).error, /valid Discord channel webhook/i);
  const invalidCode = await call(env, 'state?code=BAD!');
  assert.equal(invalidCode.status, 400);
});
