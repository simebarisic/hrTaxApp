-- external_id moze biti dulji od 64 znaka kad parser koristi fallback
-- (spoj vise polja spojenih '|') umjesto stabilnog broker ID-a.
ALTER TABLE transactions
    ALTER COLUMN external_id TYPE VARCHAR(255);
