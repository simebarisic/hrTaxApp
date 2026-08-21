-- Neki izvjestaji (npr. Revolut "Tax Report" / Income from Sells) vec dolaze
-- kao UPARENI lotovi (poznat datum stjecanja + datum prodaje po retku), a ne
-- kao sirov niz pojedinacnih BUY/SELL transakcija. Da bismo takve redove
-- svejedno proveli kroz postojeci FIFO engine bez rizika da se dva razlicita
-- retka s istim datumom slucajno krivo uparuju, svaki takav par (sintetski
-- BUY + SELL) dobiva zajednicki, JEDINSTVEN lot_group_key koji FIFO koristi
-- UMJESTO ISIN-a za grupiranje - garantira da se par uvijek upari samo sam sa
-- sobom. Za sve ostale transakcije (obicne, negrupirane) ovo polje je NULL i
-- FIFO grupira po ISIN-u kao dosad.
ALTER TABLE transactions
    ADD COLUMN lot_group_key VARCHAR(128);

CREATE INDEX idx_transactions_lot_group_key ON transactions (lot_group_key);
