CREATE TABLE transactions (
    id                    BIGSERIAL PRIMARY KEY,
    external_id           VARCHAR(64)      NOT NULL,
    source_row_index      INTEGER,
    date_time             TIMESTAMP        NOT NULL,
    type                  VARCHAR(16)      NOT NULL,
    isin                  VARCHAR(24),
    ticker                VARCHAR(32),
    name                  VARCHAR(128),
    quantity              NUMERIC(24, 8),
    price_per_share       NUMERIC(24, 8),
    currency              VARCHAR(8),
    exchange_rate_to_eur  NUMERIC(24, 8),
    fees_eur              NUMERIC(24, 4),
    total_eur             NUMERIC(24, 4),

    CONSTRAINT uq_transactions_external_id UNIQUE (external_id)
);

CREATE INDEX idx_transactions_isin ON transactions (isin);
CREATE INDEX idx_transactions_date_time ON transactions (date_time);
