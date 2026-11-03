import assert from 'node:assert/strict';
import test from 'node:test';
import worker from '../src.js';

/** Minimal D1 substitute, enough to check actual HTTP routes with real SQL call shapes. */
function fakeDB() {
  const groups = new Map(), tracked = new Map(), borrows = new Map();
  return {
    prepare(sql) {
      return {
        bind(...v) {
          return {
            async first() {
              if (sql.includes('FROM groups')) {
                const g = groups.get(v[0]);
                return g ? sql.includes('SELECT code_hash') ? { code_hash: v[0] } : { ...g } : null;
              }
              if (sql.includes('SELECT item_name FROM tracked')) return tracked.get(`${v[0]}:${v[1]}`) ?? null;
              if (sql.includes('FROM borrows')) return [...borrows.values()]
                .find(b => b.code_hash === v[0] && b.item_id === v[1] && b.status === 'borrowed') ?? null;
              return null;
            },
            async all() {
              if (sql.includes('FROM tracked')) return { results: [...tracked.entries()]
                .filter(([key]) => key.startsWith(`${v[0]}:`))
                .map(([key, b]) => ({ ...b, item_id: Number(key.split(':')[1]) })) };
              if (sql.includes('FROM borrows')) return { results: [...borrows.values()]
                .filter(b => b.code_hash === v[0] && b.status === 'borrowed') };
              return { results: [] };
            },
            async run() {
              if (sql.includes('INSERT INTO groups'))
                groups.set(v[0], { name: v[1], creator_token_hash: v[2], discord_webhook_url: null });
              else if (sql.includes('INSERT INTO tracked'))
                tracked.set(`${v[0]}:${v[1]}`, { item_id: v[1], item_name: v[2] });
              else if (sql.includes('INSERT INTO borrows'))
                borrows.set(v[0], { id: v[0], code_hash: v[1], item_id: v[2], item_name: v[3], borrower: v[4],
                  quantity: v[5], initial_quantity: v[6], show_quantity: v[7], discord_webhook_url: v[8],
                  discord_message_id: null, discord_attachments: '[]', status: 'borrowed', created_at: '2026-11-03' });
              else if (sql.includes('UPDATE borrows SET quantity')) {
                const b = borrows.get(v[3]);
                b.quantity = v[0]; b.status = v[1];
              }
              return { success: true };
            }
          };
        }
      };
    }
  };
}

async function request(env, path, method = 'GET', body = null) {
  const response = await worker.fetch(new Request(`https://gim.test/${path}`, {
    method, headers: { 'content-type': 'application/json' },
    ...(body === null ? {} : { body: JSON.stringify(body) })
  }), env);
  const data = await response.json();
  assert.ok(response.ok, `${path}: ${JSON.stringify(data)}`);
  return data;
}

test('tracked Brimstone ring saved withdrawal becomes borrowed, saved deposit becomes returned', async () => {
  const env = { DB: fakeDB() };
  const { code } = await request(env, 'groups', 'POST', { name: 'TEST' });
  await request(env, 'track', 'POST', { code, itemId: 11980, name: 'Brimstone ring' });
  await request(env, 'movement', 'POST', { code, itemId: 11980, itemName: 'Brimstone ring', player: 'bigpapaclark', delta: -1 });
  const afterBorrow = await request(env, `state?code=${code}`);
  assert.equal(afterBorrow.borrowed.length, 1);
  assert.equal(afterBorrow.borrowed[0].borrower, 'bigpapaclark');
  assert.equal(afterBorrow.borrowed[0].quantity, 1);
  await request(env, 'movement', 'POST', { code, itemId: 11980, itemName: 'Brimstone ring', player: 'bigpapaclark', delta: 1 });
  const afterReturn = await request(env, `state?code=${code}`);
  assert.deepEqual(afterReturn.borrowed, []);
  assert.equal(afterReturn.tracked.length, 1);
});
