ALTER TABLE transactions
    ADD COLUMN broker VARCHAR(16) NOT NULL DEFAULT 'TRADING212';

ALTER TABLE transactions
    ALTER COLUMN broker DROP DEFAULT;

CREATE INDEX idx_transactions_broker ON transactions (broker);
