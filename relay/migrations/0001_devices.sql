CREATE TABLE devices (
  uid TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  fcm_token TEXT NOT NULL UNIQUE,
  subscription_status TEXT NOT NULL DEFAULT 'unavailable',
  registered_at INTEGER NOT NULL,
  last_delivery_at INTEGER,
  last_revision TEXT,
  last_error TEXT
);
CREATE INDEX devices_account ON devices(account_id);
CREATE TABLE deliveries (
  revision TEXT NOT NULL,
  uid TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  sent_at INTEGER,
  error TEXT,
  PRIMARY KEY(revision, uid)
);
