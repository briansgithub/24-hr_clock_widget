ALTER TABLE devices ADD COLUMN provider TEXT NOT NULL DEFAULT 'fitbit';
CREATE INDEX devices_provider_account ON devices(provider, account_id);
