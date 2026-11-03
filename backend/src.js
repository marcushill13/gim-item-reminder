// GIM Item Reminder: Cloudflare Worker for an opt-in shared group ledger and Discord posts.
// Screenshots are forwarded to Discord; they are not saved to D1/R2 by this Worker.
const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { 'content-type': 'application/json', 'access-control-allow-origin': '*' }
});
const err = (message, status = 400) => json({ error: message }, status);
const encoder = new TextEncoder();
// Human-friendly six-character codes; excludes ambiguous characters.
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
function newInviteCode() {
  const digits = new Uint8Array(6);
  crypto.getRandomValues(digits);
  return [...digits].map(d => CODE_ALPHABET[d % CODE_ALPHABET.length]).join('');
}
function validWebhook(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && ['discord.com', 'discordapp.com'].includes(url.hostname)
      && /^\/api\/webhooks\/\d+\/[^/?#]+\/?$/.test(url.pathname) && !url.search && !url.hash;
  } catch { return false; }
}


async function digest(code) {
  const hash = await crypto.subtle.digest('SHA-256', encoder.encode(code));
  return [...new Uint8Array(hash)].map(b => b.toString(16).padStart(2, '0')).join('');
}

function safeText(value) {
  return String(value ?? '').replace(/[\\*_`~|<>]/g, '\\$&').slice(0, 100);
}

// Limit size even if a client mistakenly sends a full-resolution screenshot.
// The PNG is a single RuneLite item sprite, the JPG is a compressed game canvas.
export function imageFromBase64(encoded, mime, maxBytes) {
  if (!encoded || typeof encoded !== 'string') return null;
  if (encoded.length > Math.ceil(maxBytes / 3) * 4 + 16 || !/^[a-zA-Z0-9+/]+={0,2}$/.test(encoded)) return null;
  try {
    const bytes = Uint8Array.from(atob(encoded), c => c.charCodeAt(0));
    return bytes.length > 0 && bytes.length <= maxBytes ? new Blob([bytes], { type: mime }) : null;
  } catch { return null; }
}

function retainAttachments(attachments) {
  return (Array.isArray(attachments) ? attachments : [])
    .filter(a => a && /^\d+$/.test(String(a.id)) && /^[a-zA-Z0-9._-]{1,80}$/.test(a.filename))
    .map(a => ({ id: String(a.id), filename: a.filename }));
}

export function buildEmbeds(record, returned, files) {
  const label = returned ? '🟢 RETURNED' : '🔴 CURRENTLY BORROWED';
  const item = safeText(record.item_name);
  const player = safeText(record.borrower);
  const statusEmbed = {
    title: label,
    description: returned
      ? `**${item}** has been returned to Group Storage.`
      : `**${player}** has borrowed **${item}** from Group Storage.`,
    color: returned ? 0x23a55a : 0xed4245,
    fields: [
      { name: 'Item', value: item, inline: true },
      { name: 'Borrower', value: player, inline: true },
      ...(record.show_quantity === 0 ? [] : [{ name: 'Quantity', value: String(record.initial_quantity), inline: true }]),
      { name: 'Status', value: `**${label}**`, inline: false }
    ],
    footer: { text: `GIM Item Reminder • ${safeText(record.group_name ?? 'Group Storage')}` }
  };
  if (files.has('item.png')) statusEmbed.thumbnail = { url: 'attachment://item.png' };
  if (files.has('borrow.jpg')) statusEmbed.image = { url: 'attachment://borrow.jpg' };
  const embeds = [statusEmbed];
  if (returned && files.has('return.jpg')) {
    embeds.push({ title: 'Return screenshot', color: 0x23a55a, image: { url: 'attachment://return.jpg' } });
  }
  return embeds;
}

/** Send a Discord webhook or PATCH its message, preserving previously uploaded image files. */
export async function sendDiscord(env, record, returned, options = {}, fetcher = fetch) {
  const webhook = options.webhookUrl;
  if (!webhook) return null;
  if (!validWebhook(webhook)) {
    throw new Error('Invalid Discord webhook URL');
  }
  const url = new URL(webhook);
  const messageId = options.messageId;
  if (messageId) url.pathname = `${url.pathname.replace(/\/$/, '')}/messages/${encodeURIComponent(messageId)}`;
  else url.searchParams.set('wait', 'true');

  // Discord may omit embed-only images from its message.attachments array.
  // Editing with an incomplete attachments list can drop the old thumbnail.
  // On return, re-upload the small item sprite instead of assuming that the
  // original embedded attachment survives a PATCH. Preserve other files.
  let attachments = retainAttachments(options.attachments);
  const newFiles = [];
  if (!messageId) {
    const sprite = imageFromBase64(options.iconBase64, 'image/png', 100_000);
    if (sprite) newFiles.push({ name: 'item.png', blob: sprite });
    const shot = imageFromBase64(options.screenshotBase64, 'image/jpeg', 1_500_000);
    if (shot) newFiles.push({ name: 'borrow.jpg', blob: shot });
  } else if (returned) {
    const sprite = imageFromBase64(options.iconBase64, 'image/png', 100_000);
    if (sprite) {
      attachments = attachments.filter(a => a.filename !== 'item.png');
      newFiles.push({ name: 'item.png', blob: sprite });
    }
    const shot = imageFromBase64(options.screenshotBase64, 'image/jpeg', 1_500_000);
    if (shot && !attachments.some(a => a.filename === 'return.jpg')) newFiles.push({ name: 'return.jpg', blob: shot });
  }
  const names = new Set([...attachments.map(a => a.filename), ...newFiles.map(a => a.name)]);

  const payload = {
    embeds: buildEmbeds(record, returned, names),
    allowed_mentions: { parse: [] },
    ...(messageId ? { attachments: [
      ...attachments,
      ...newFiles.map((f, index) => ({ id: index, filename: f.name }))
    ] } : {})
  };
  let init;
  if (newFiles.length) {
    const form = new FormData();
    form.append('payload_json', JSON.stringify(payload));
    for (const [index, file] of newFiles.entries()) {
      form.append(`files[${index}]`, file.blob, file.name);
    }
    init = { method: messageId ? 'PATCH' : 'POST', body: form };
  } else {
    init = { method: messageId ? 'PATCH' : 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload) };
  }
  const result = await fetcher(url, init);
  if (!result.ok) throw new Error(`Discord responded ${result.status}: ${(await result.text()).slice(0, 350)}`);
  const body = await result.json(); // POST uses wait=true, PATCH returns the edited message.
  return { messageId: body.id ?? messageId ?? null, attachments: retainAttachments(body.attachments) };
}

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      const path = url.pathname.replace(/\/+$/, '') || '/';
      if (path === '/health' && request.method === 'GET') return json({ ok: true, service: 'gim-item-reminder', api_version: 5 });
      const contentLength = Number(request.headers.get('content-length') || 0);
      if (contentLength > 4_000_000) return err('Request too large', 413);
      const body = request.method === 'GET' ? {} : await request.json();
      // The Worker, not the client, issues six-character invite codes.
      if (path === '/groups' && request.method === 'POST') {
        const name = String(body.name || '').trim().slice(0, 80);
        if (!name) return err('Group name is required');
        const creatorToken = crypto.randomUUID() + crypto.randomUUID();
        const tokenHash = await digest(creatorToken);
        for (let attempt = 0; attempt < 12; attempt++) {
          const inviteCode = newInviteCode();
          const inviteKey = await digest(inviteCode);
          const existing = await env.DB.prepare('SELECT code_hash FROM groups WHERE code_hash=?').bind(inviteKey).first();
          if (existing) continue;
          try {
            await env.DB.prepare('INSERT INTO groups(code_hash,name,creator_token_hash) VALUES(?,?,?)')
              .bind(inviteKey, name, tokenHash).run();
            return json({ ok: true, name, code: inviteCode, creatorToken }, 201);
          } catch (e) {
            // If an insertion raced with another group, retry with another code.
            if (!/unique|constraint/i.test(String(e.message))) throw e;
          }
        }
        return err('Unable to create invite code; try again.', 503);
      }
      const code = String(request.headers.get('X-GIM-Group-Code') || body.code || url.searchParams.get('code') || '').toUpperCase();
      if (!/^[A-Z2-9]{6}$/.test(code)) return err('Enter the six-character group code.');
      const key = await digest(code);
      const group = await env.DB.prepare('SELECT name,creator_token_hash,discord_webhook_url FROM groups WHERE code_hash=?').bind(key).first();
      if (!group) return err('No group found with that code.', 404);
      if (path === '/state' && request.method === 'GET') {
        const tracked = await env.DB.prepare('SELECT item_id,item_name FROM tracked WHERE code_hash=? ORDER BY item_name').bind(key).all();
        const borrowed = await env.DB.prepare("SELECT id,item_id,item_name,borrower,quantity,created_at FROM borrows WHERE code_hash=? AND status='borrowed' ORDER BY created_at").bind(key).all();
        return json({ name: group.name, tracked: tracked.results, borrowed: borrowed.results,
          discord_connected: !!group.discord_webhook_url });
      }
      if (path.startsWith('/discord')) {
        // The invite code alone must NEVER be enough to change the group's webhook.
        const token = String(request.headers.get('X-GIM-Creator-Token') || body.creatorToken || '');
        if (!group.creator_token_hash || token.length < 40 || await digest(token) !== group.creator_token_hash)
          return err('Only the group creator can change Discord settings on this device.', 403);
        if (path === '/discord' && request.method === 'PUT') {
          const webhook = String(body.webhookUrl || '').trim();
          if (!validWebhook(webhook)) return err('Paste a valid Discord channel webhook URL.');
          await env.DB.prepare('UPDATE groups SET discord_webhook_url=? WHERE code_hash=?').bind(webhook, key).run();
          return json({ ok: true, discord_connected: true });
        }
        if (path === '/discord' && request.method === 'DELETE') {
          await env.DB.prepare('UPDATE groups SET discord_webhook_url=NULL WHERE code_hash=?').bind(key).run();
          return json({ ok: true, discord_connected: false });
        }
        if (path === '/discord/test' && request.method === 'POST') {
          if (!group.discord_webhook_url) return err('Add a Discord webhook first.');
          try {
            const res = await fetch(group.discord_webhook_url, {
              method: 'POST', headers: { 'content-type': 'application/json' },
              body: JSON.stringify({ embeds: [{ title: 'GIM Item Reminder',
                description: `✅ Discord connected to **${safeText(group.name)}**.`, color: 0x23a55a }],
                allowed_mentions: { parse: [] } }) });
            if (!res.ok) return err('Discord could not deliver the test message (' + res.status + ').', 502);
            return json({ ok: true });
          } catch { return err('Discord test failed; check that the webhook is still valid.', 502); }
        }
        return err('Unknown Discord action', 404);
      }
      if (path === '/track' && request.method === 'POST') {
        const id = Number(body.itemId);
        const name = String(body.name || '').trim().slice(0, 100);
        if (!Number.isSafeInteger(id) || id < 1 || !name) return err('Invalid item');
        await env.DB.prepare('INSERT INTO tracked(code_hash,item_id,item_name) VALUES(?,?,?) ON CONFLICT(code_hash,item_id) DO UPDATE SET item_name=excluded.item_name').bind(key, id, name).run();
        return json({ ok: true });
      }
      if (path === '/untrack' && request.method === 'POST') {
        const id = Number(body.itemId);
        if (!Number.isSafeInteger(id) || id < 1) return err('Invalid item ID');
        await env.DB.prepare('DELETE FROM tracked WHERE code_hash=? AND item_id=?').bind(key, id).run();
        return json({ ok: true });
      }
      if (path === '/movement' && request.method === 'POST') {
        const id = Number(body.itemId);
        const delta = Number(body.delta);
        const player = String(body.player || '').trim().slice(0, 20);
        if (!Number.isSafeInteger(id) || id < 1 || !Number.isSafeInteger(delta) || delta === 0 || Math.abs(delta) > 100000 || !player)
          return err('Invalid movement');
        const tracked = await env.DB.prepare('SELECT item_name FROM tracked WHERE code_hash=? AND item_id=?').bind(key, id).first();
        if (!tracked) return json({ ok: true, ignored: 'not tracked' });
        if (delta < 0) {
          const row = { id: crypto.randomUUID(), item_id: id, item_name: tracked.item_name, borrower: player,
            quantity: -delta, initial_quantity: -delta, group_name: group.name, show_quantity: body.showQuantity === false ? 0 : 1 };
          await env.DB.prepare('INSERT INTO borrows(id,code_hash,item_id,item_name,borrower,quantity,initial_quantity,show_quantity,discord_webhook_url) VALUES(?,?,?,?,?,?,?,?,?)')
            .bind(row.id, key, id, row.item_name, player, row.quantity, row.quantity, row.show_quantity, group.discord_webhook_url).run();
          try {
            const result = await sendDiscord(env, row, false,
              { iconBase64: body.iconBase64, screenshotBase64: body.screenshotBase64, webhookUrl: group.discord_webhook_url });
            if (result?.messageId) await env.DB.prepare('UPDATE borrows SET discord_message_id=?,discord_attachments=? WHERE id=?')
              .bind(result.messageId, JSON.stringify(result.attachments), row.id).run();
          } catch (e) { console.error('Discord borrow post failed', e); }
        } else {
          let remaining = delta;
          while (remaining > 0) {
            const row = await env.DB.prepare("SELECT * FROM borrows WHERE code_hash=? AND item_id=? AND status='borrowed' ORDER BY created_at,id LIMIT 1").bind(key, id).first();
            if (!row) break;
            const count = Math.min(remaining, row.quantity);
            const next = row.quantity - count;
            await env.DB.prepare("UPDATE borrows SET quantity=?,status=?,returned_at=CASE WHEN ?=0 THEN CURRENT_TIMESTAMP ELSE NULL END WHERE id=?")
              .bind(next, next ? 'borrowed' : 'returned', next, row.id).run();
            if (next === 0 && row.discord_message_id) {
              try {
                const attachments = JSON.parse(row.discord_attachments || '[]');
                const result = await sendDiscord(env, { ...row, group_name: group.name }, true,
                  { messageId: row.discord_message_id, attachments, iconBase64: body.iconBase64,
                    screenshotBase64: body.screenshotBase64, webhookUrl: row.discord_webhook_url });
                if (result) await env.DB.prepare('UPDATE borrows SET discord_attachments=? WHERE id=?')
                  .bind(JSON.stringify(result.attachments), row.id).run();
              } catch (e) { console.error('Discord return edit failed', e); }
            }
            remaining -= count;
          }
        }
        return json({ ok: true });
      }
      return err('Route not found', 404);
    } catch (e) {
      console.error('Request failed', e);
      return err('Request failed', 500);
    }
  }
};
