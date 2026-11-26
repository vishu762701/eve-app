import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { Hono } from 'hono';
import { adminRoutes } from '../src/routes/admin.ts';
import { HARDCODED_ADMIN_EMAILS } from '../src/auth.ts';

function setupModernUsersDb({ withPhotoUrl = false } = {}) {
  const sql = new DatabaseSync(':memory:');
  sql.exec(`CREATE TABLE users (
    id TEXT PRIMARY KEY,
    eve_id TEXT,
    email TEXT,
    display_name TEXT,
    dob TEXT,
    category TEXT,
    username TEXT,
    phone TEXT,
    bio TEXT NOT NULL DEFAULT '',
    ${withPhotoUrl ? 'photo_url TEXT,' : ''}
    created_at INTEGER,
    last_active INTEGER,
    disabled INTEGER DEFAULT 0
  )`);
  sql.exec('CREATE TABLE admins (email TEXT PRIMARY KEY)');
  sql.prepare('INSERT INTO admins (email) VALUES (?)').run([...HARDCODED_ADMIN_EMAILS][0]);

  if (withPhotoUrl) {
    sql.prepare(
      `INSERT INTO users (id, eve_id, email, display_name, dob, category, username, phone, bio, photo_url, created_at, last_active, disabled)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).run(
      'u1', 'EV-ABC123', 'rahul@example.com', 'Rahul Sharma', '2001-05-12', 'UPSC',
      'rahul_upsc', '+919876543210', 'Target CSE 2027', 'https://cdn.example.com/rahul.png',
      1700000000000, 1700005000000, 0
    );
  } else {
    sql.prepare(
      `INSERT INTO users (id, eve_id, email, display_name, dob, category, username, phone, bio, created_at, last_active, disabled)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).run(
      'u1', 'EV-ABC123', 'rahul@example.com', 'Rahul Sharma', '2001-05-12', 'UPSC',
      'rahul_upsc', '+919876543210', 'Target CSE 2027',
      1700000000000, 1700005000000, 0
    );
  }
  sql.prepare(
    `INSERT INTO users (id, eve_id, email, display_name, dob, category, username, phone, bio, ${withPhotoUrl ? 'photo_url,' : ''} created_at, last_active, disabled)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ${withPhotoUrl ? '?,' : ''} ?, ?, ?)`
  ).run(
    ...(withPhotoUrl
      ? ['u2', 'EV-XYZ789', 'priya@example.com', 'Priya Verma', '', 'SSC', 'priya_ssc', '', '', '', 1700000001000, 1700004000000, 1]
      : ['u2', 'EV-XYZ789', 'priya@example.com', 'Priya Verma', '', 'SSC', 'priya_ssc', '', '', 1700000001000, 1700004000000, 1])
  );

  const DB = {
    prepare(statement) {
      const args = [];
      return {
        bind(...values) { args.push(...values); return this; },
        async first() { return sql.prepare(statement).get(...args) ?? null; },
        async all() { return { results: sql.prepare(statement).all(...args) }; },
        async run() {
          const changes = sql.prepare(statement).run(...args).changes;
          return { meta: { changes: Number(changes) } };
        },
      };
    },
  };

  const app = new Hono();
  app.use('*', async (c, next) => {
    c.set('user', { uid: 'admin', email: [...HARDCODED_ADMIN_EMAILS][0], isAdmin: true });
    await next();
  });
  app.route('/api/admin', adminRoutes);

  return {
    async request(query = '') {
      const response = await app.request('/api/admin/users' + query, {}, { DB });
      return { status: response.status, json: await response.json() };
    },
    close: () => sql.close(),
  };
}

test('GET /api/admin/users returns username, phone, bio, photoUrl and searches @username', async () => {
  const f = setupModernUsersDb({ withPhotoUrl: true });
  try {
    const all = await f.request('?page=1&pageSize=50');
    assert.equal(all.status, 200);
    assert.equal(all.json.pagination.total, 2);
    assert.equal(all.json.data[0].username, 'rahul_upsc');
    assert.equal(all.json.data[0].phone, '+919876543210');
    assert.equal(all.json.data[0].bio, 'Target CSE 2027');
    assert.equal(all.json.data[0].photoUrl, 'https://cdn.example.com/rahul.png');

    const byHandle = await f.request('?page=1&pageSize=50&q=' + encodeURIComponent('@rahul_upsc'));
    assert.equal(byHandle.status, 200);
    assert.equal(byHandle.json.pagination.total, 1);
    assert.equal(byHandle.json.data[0].id, 'u1');

    const bySubHandle = await f.request('?page=1&pageSize=50&q=priya_ssc');
    assert.equal(bySubHandle.status, 200);
    assert.equal(bySubHandle.json.pagination.total, 1);
    assert.equal(bySubHandle.json.data[0].id, 'u2');
    assert.equal(bySubHandle.json.data[0].disabled, true);
  } finally {
    f.close();
  }
});

test('GET /api/admin/users safely falls back to empty photoUrl when photo_url column is absent', async () => {
  const f = setupModernUsersDb({ withPhotoUrl: false });
  try {
    const res = await f.request('?page=1&pageSize=50&q=rahul');
    assert.equal(res.status, 200);
    assert.equal(res.json.data.length, 1);
    assert.equal(res.json.data[0].username, 'rahul_upsc');
    assert.equal(res.json.data[0].phone, '+919876543210');
    assert.equal(res.json.data[0].bio, 'Target CSE 2027');
    assert.equal(res.json.data[0].photoUrl, '');
  } finally {
    f.close();
  }
});
