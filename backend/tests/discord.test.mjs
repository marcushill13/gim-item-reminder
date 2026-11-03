import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sendDiscord, imageFromBase64, buildEmbeds } from '../src.js';

const env = {};
const defaultWebhook = 'https://discord.com/api/webhooks/1234/secret';
const record = { item_name: "Osmumten's fang", borrower: 'bigpapaclark', initial_quantity: 1, show_quantity: 1, group_name: 'Iron Friends' };
const png = Buffer.from('89504e470d0a1a0a', 'hex').toString('base64');
const jpg = Buffer.from('ffd8ffe0', 'hex').toString('base64');

function fakeRequestCapture(log) {
  return async (url, init) => {
    let body;
    if (init.body instanceof FormData) body = JSON.parse(init.body.get('payload_json'));
    else body = JSON.parse(init.body);
    log.push({ url: url.toString(), method: init.method, payload: body, files: init.body instanceof FormData ? [...init.body.keys()].filter(k => k.startsWith('files[')) : [] });
    const old = body.attachments?.filter(a => String(a.id) !== '0') ?? [];
    const novel = (init.body instanceof FormData ? [...init.body.entries()].filter(([key]) => key.startsWith('files[')) : [])
      .map(([key, file], index) => ({ id: String(9000 + index), filename: file.name }));
    return new Response(JSON.stringify({ id: '777777', attachments: [...old, ...novel] }), { status: 200 });
  };
}

test('borrow uploads item thumbnail and canvas screenshot', async () => {
  const calls = [];
  const sent = await sendDiscord(env, record, false, { webhookUrl: defaultWebhook, iconBase64: png, screenshotBase64: jpg }, fakeRequestCapture(calls));
  assert.equal(sent.messageId, '777777');
  assert.equal(calls[0].method, 'POST');
  assert.equal(calls[0].payload.embeds[0].color, 0xed4245);
  assert.deepEqual(calls[0].files, ['files[0]', 'files[1]']);
  assert.equal(calls[0].payload.embeds[0].thumbnail.url, 'attachment://item.png');
  assert.equal(calls[0].payload.embeds[0].image.url, 'attachment://borrow.jpg');
  assert.equal(sent.attachments.length, 2);
});

test('return edits same post and retains prior images', async () => {
  const calls = [];
  const requester = fakeRequestCapture(calls);
  const sent = await sendDiscord(env, record, false, { webhookUrl: defaultWebhook, iconBase64: png, screenshotBase64: jpg }, requester);
  await sendDiscord(env, record, true, { webhookUrl: defaultWebhook, messageId: sent.messageId, attachments: sent.attachments }, requester);
  assert.equal(calls[1].method, 'PATCH');
  assert.match(calls[1].url, /messages\/777777$/);
  assert.equal(calls[1].payload.embeds[0].color, 0x23a55a);
  assert.match(calls[1].payload.embeds[0].title, /RETURNED/);
  assert.equal(calls[1].payload.embeds[0].thumbnail.url, 'attachment://item.png');
  assert.equal(calls[1].payload.embeds[0].image.url, 'attachment://borrow.jpg');
  assert.equal(calls[1].payload.attachments.length, 2);
});

test('optional return screenshot adds a second image without losing borrow screenshot', async () => {
  const calls = [];
  const requester = fakeRequestCapture(calls);
  const sent = await sendDiscord(env, record, false, { webhookUrl: defaultWebhook, iconBase64: png, screenshotBase64: jpg }, requester);
  await sendDiscord(env, record, true, { webhookUrl: defaultWebhook, messageId: sent.messageId, attachments: sent.attachments, screenshotBase64: jpg }, requester);
  assert.equal(calls[1].payload.embeds[1].image.url, 'attachment://return.jpg');
  assert.equal(calls[1].payload.attachments.length, 3);
  assert.deepEqual(calls[1].files, ['files[0]']);
});

test('invalid image data is ignored, quantity can be hidden, and no screenshot is required', async () => {
  const calls = [];
  const sent = await sendDiscord(env, { ...record, show_quantity: 0 }, false, { webhookUrl: defaultWebhook, iconBase64: '@@invalid@@' }, fakeRequestCapture(calls));
  assert.equal(sent.attachments.length, 0);
  assert.equal(calls[0].payload.embeds[0].fields.some(x => x.name === 'Quantity'), false);
  assert.equal(calls[0].payload.embeds[0].thumbnail, undefined);
  assert.equal(imageFromBase64('!!', 'image/png', 2000), null);
  assert.equal(buildEmbeds(record, true, new Set())[0].color, 0x23a55a);
});

test('reject URLs other than genuine Discord webhook endpoints', async () => {
  await assert.rejects(sendDiscord({}, record, false, { webhookUrl: 'https://evil.example/api/webhooks/123/secret' }, fakeRequestCapture([])), /Invalid Discord/);
});

test('group webhook overrides any global default for notification delivery', async () => {
  const calls = [];
  await sendDiscord(env, record, false,
    { webhookUrl: 'https://discord.com/api/webhooks/2222/group-only' }, fakeRequestCapture(calls));
  assert.match(calls[0].url, /\/2222\/group-only/);
  assert.doesNotMatch(calls[0].url, /1234/);
});


test('return reuploads item icon so the thumbnail survives a Discord edit', async () => {
  const calls = [];
  const requester = fakeRequestCapture(calls);
  const sent = await sendDiscord(env, record, false,
    { webhookUrl: defaultWebhook, iconBase64: png }, requester);
  await sendDiscord(env, record, true,
    { webhookUrl: defaultWebhook, messageId: sent.messageId,
      attachments: sent.attachments, iconBase64: png }, requester);
  assert.equal(calls[1].method, 'PATCH');
  assert.deepEqual(calls[1].files, ['files[0]']);
  assert.equal(calls[1].payload.embeds[0].thumbnail.url, 'attachment://item.png');
  assert.equal(calls[1].payload.attachments.some(a => a.filename === 'item.png'), true);
});

test('return retains other files while replacing old item icon, without duplicates', async () => {
  const calls = [];
  const original = [
    { id: '12345678', filename: 'item.png' },
    { id: '98765432', filename: 'borrow.jpg' }
  ];
  await sendDiscord(env, record, true,
    { webhookUrl: defaultWebhook, messageId: '888', attachments: original, iconBase64: png }, fakeRequestCapture(calls));
  assert.deepEqual(calls[0].files, ['files[0]']);
  assert.deepEqual(calls[0].payload.attachments.map(a => a.filename), ['borrow.jpg', 'item.png']);
  assert.equal(calls[0].payload.embeds[0].thumbnail.url, 'attachment://item.png');
  assert.equal(calls[0].payload.embeds[0].image.url, 'attachment://borrow.jpg');
});

test('return adds an item icon even when Discord omitted embed-only attachments', async () => {
  const calls = [];
  const requester = fakeRequestCapture(calls);
  await sendDiscord(env, record, true,
    { webhookUrl: defaultWebhook, messageId: '888', attachments: [], iconBase64: png }, requester);
  assert.equal(calls[0].payload.embeds[0].thumbnail.url, 'attachment://item.png');
  assert.deepEqual(calls[0].payload.attachments.map(a => a.filename), ['item.png']);
});
