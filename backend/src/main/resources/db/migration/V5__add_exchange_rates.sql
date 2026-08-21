-- Kes za HNB tecajeve (srednji tecaj), da se ne zove vanjski API ponovno za
-- istu (valuta, datum) kombinaciju - i da aplikacija i dalje radi za vec
-- keširane datume i ako HNB API privremeno ne odgovara.
CREATE TABLE exchange_rates (
    id           BIGSERIAL PRIMARY KEY,
    currency     VARCHAR(8)     NOT NULL,
    rate_date    DATE           NOT NULL,
    eur_rate     NUMERIC(18, 6) NOT NULL,
    fetched_at   TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT uq_exchange_rates_currency_date UNIQUE (currency, rate_date)
);
