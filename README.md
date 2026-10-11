# Porez na kapitalnu dobit — Trading212 → JOPPD priprema

Spring Boot + React aplikacija koja iz Trading212 CSV exporta izračuna
hrvatski porez na kapitalnu dobit (12%, izuzeće nakon 2 godine, FIFO
metoda, prijeboj dobitaka/gubitaka unutar godine) i priprema podatke
za JOPPD obrazac.

**Ovo NIJE porezni savjet niti službeni alat Porezne uprave.** Prije
podnošenja obrasca, provjeri brojke sa svojim knjigovođom/poreznim
savjetnikom, a šifre/rokove JOPPD-a s aktualnom Naredbom Porezne
uprave. Aplikacija ne šalje ništa elektronički u ePoreznu — samo
priprema brojke za ručni unos.

## Struktura

```
backend/    Spring Boot 3 (Java 21) - CSV parsing, FIFO, porezni izračun, REST API
frontend/   React (Vite) - upload i prikaz rezultata
```

## Najbrži start (Docker, cijeli stack)

Preduvjet: Docker + Docker Compose.

```bash
docker compose up -d --build
```

Ovo diže Postgres + backend + frontend zajedno:
- Frontend: `http://localhost:8081`
- Backend API: `http://localhost:8080`
- Postgres: `localhost:5432`
(sve samo na localhostu — vidi "Sigurnost")

Shema baze se automatski kreira preko Flyway migracija pri prvom
pokretanju backenda. Za zaustavljanje: `docker compose down` (dodaj
`-v` ako želiš obrisati i podatke iz baze).

Prvo: `cp .env.example .env` i postavi `DB_PASSWORD`.

Za razvoj je često zgodnije pokrenuti **samo Postgres** u Dockeru, a
backend/frontend lokalno s hot-reloadom (vidi sekcije ispod) —
`docker compose up -d postgres`.

## Kako dobiti Trading212 CSV

U Trading212 aplikaciji: **Menu → History → Export** → odaberi period
(preporučeno: OD POČETKA trgovanja, jer FIFO obračun treba cijelu
povijest kupnji da bi ispravno uparivao prodaje) → odaberi kategorije
(barem "Orders"/transactions) → Generate → preuzmi `.csv`.

Napomena: export za **CFD račune trenutno nije podržan** (CFD dobici
imaju drugačiji porezni tretman u RH, nisu "financijska imovina" iz
čl. 67. ZPD-a).

## Podržani brokeri i formati

| Broker | CSV | PDF | Status |
|---|---|---|---|
| Trading212 | ✅ | ⚠️ eksperimentalno | CSV verificiran na stvarnim podacima |
| Revolut | ✅ | ❌ | Verificiran na stvarnom "Tax Report" exportu |
| IBKR | ⚠️ neverificirano | ❌ | CSV parser za Flex Query, nije testiran na pravom exportu |
| Strike (Bitcoin) | ⚠️ neverificirano | ❌ | CSV transakcija; vidi sekciju "Bitcoin (Strike)" |

**Kako doći do exporta po brokeru:**
- **Trading212 CSV**: Menu → History → Export (vidi gore)
- **Trading212 PDF**: "Izvod aktivnosti trgovanja" (Activity Statement) — parser čita
  sekciju "BURZOVNE I OTC TRANSAKCIJE" iz teksta izvučenog PDFBox-om. Krhkije od CSV-a
  (vidi napomenu u kodu `Trading212PdfParser`) — ako ne prepozna transakcije, prijavi
  problem pa prilagođavamo regex prema stvarnom PDFBox izlazu.
- **Revolut CSV**: u aplikaciji, Trading → Statements → generiraj **Tax Report** kao CSV.
  Format je multi-sekcijski (odvojen praznim retkom) — "Income from Sells" (već uparени
  BUY/SELL lotovi s datumom stjecanja/prodaje) i "Other income & fees" (dividende).
  Nepoznate sekcije (npr. kamate, custody fee) se trenutno tiho preskaču.
- **IBKR CSV**: Client Portal → Performance & Reports → Flex Queries → napravi
  "Trades" Flex Query s poljima Symbol, ISIN, TradeDate, Buy/Sell, Quantity,
  TradePrice, CurrencyPrimary, IBCommission, AssetCategory, TradeID → export CSV.
  Trenutno se obrađuju samo dionice/ETF-ovi (AssetCategory=STK); opcije/futures
  se preskaču.

**Valute:** Revolut, IBKR i Strike (ako je u USD) se pretvaraju u EUR HNB tečajem na dan transakcije.
Trading212 "Total" se pretvara HNB tečajem samo ako stupac `Currency (Total)` nije EUR (npr. GBP/USD račun).
Ako konverzija ne uspije (HNB nedostupan, nepoznat tečaj, **datum prije 2023.** kad HNB EUR-lista ne postoji),
transakcija se **ne sprema** nego ide u **Karantenu** s razlogom — umjesto da se sirov iznos tiho tretira kao EUR.

Za IBKR, kao i kod Trading212-a i Revoluta u početku, prva runda testiranja na
stvarnom exportu vjerojatno će zahtijevati podešavanje naziva kolona/formata
datuma u parseru — to je očekivano, ne znak da je arhitektura loša.

## Bitcoin (Strike)

Prodaja Bitcoina je porezni događaj i ulazi u isti obračun kao dionice (FIFO, 12%, izuzeće nakon 2 godine,
netiranje dobitaka/gubitaka unutar godine **zajedno s dionicama**).

- **Upload**: odaberi "Strike (Bitcoin)" i uploadaj CSV export transakcija. `StrikeCsvParser` traži stupce po
  nazivu (vrijeme, vrsta transakcije, iznos u BTC, iznos u EUR/USD, naknada, ID, status).
  Purchase/Buy → BUY, Sale/Sell → SELL; vrijeme se pretvara iz UTC u hrvatsko vrijeme.
- **Cold wallet**: prijenos Strike → cold wallet (i natrag) **nije porezni događaj** — Withdrawal/Deposit/Send/
  Receive se preskaču. FIFO za kripto koristi **jedan zajednički pool** ("CRYPTO") neovisno o platformi, pa BTC
  kupljen na Strikeu, prebačen na wallet i vraćen na Strike radi prodaje zadržava izvorni datum i cijenu nabave.
- **Naknada**: `app.strike.fee-included-in-amount` (env `APP_STRIKE_FEE_INCLUDED_IN_AMOUNT`, default `true`).
  `true` = "Amount EUR" već sadrži naknadu; `false` = kod kupnje se naknada dodaje, kod prodaje oduzima.
  **Provjeri na jednoj stvarnoj transakciji** (EUR amount vs. BTC × cijena) i po potrebi promijeni.
- **Ručni unos**: ako CSV format ne prolazi ili je BTC kupljen izvan Strikea (burza, P2P), koristi "Ručni unos"
  u UI-ju (`POST /api/reports/transactions`, brisanje `DELETE /api/reports/transactions/{id}`). Unosi se UKUPNO u EUR.
- **Upozorenja**: ako prodaješ više BTC-a nego što je evidentirano kupljeno, prikazuje se upozorenje (nedostaje
  povijest kupnji, npr. BTC kupljen prije Strikea ili primljen na wallet).
- **Ograničenja**: slanje BTC-a trećoj osobi / plaćanje BTC-om je tehnički raspolaganje imovinom, ali se u exportu ne
  razlikuje od prijenosa na vlastiti wallet pa se preskače; on-chain naknade za prijenos (mali iznosi satoshija) se
  ne obračunavaju; zamjena kripto → kripto (npr. BTC → ETH) nije podržana. **Format Strike exporta nije
  verificiran na stvarnim podacima** — ako nešto ne prođe, pogledaj Karantenu ili pošalji zaglavlje + par redaka.
- Porezni tretman kriptoimovine (stopa, FIFO, izuzeće nakon 2 godine, rok prijave) **provjeri s knjigovođom**.

## Sigurnost

Aplikacija **nema autentikaciju**, a drži stvarne financijske podatke.

- `docker-compose.yml` veže Postgres (5432), backend (8080) i frontend (8081) **samo na 127.0.0.1**.
- Lozinka baze se čita iz `.env` (kopiraj `.env.example` → `.env`); compose se neće pokrenuti bez `DB_PASSWORD`.
- Za pristup s drugog uređaja (npr. Raspberry Pi homelab): postavi `FRONTEND_BIND=0.0.0.0` u `.env` **i** uključi
  basic auth: `./scripts/create-htpasswd.sh <korisnik>` pa
  `docker compose -f docker-compose.yml -f docker-compose.auth.yml up -d --build`.
  Basic auth bez TLS-a šalje lozinku u čistom tekstu — za pristup izvan kućne mreže koristi VPN (npr. WireGuard)
  ili reverse proxy s TLS-om.

## Napomena o vremenskoj zoni (promjena ponašanja)

Trading212 i Strike izvoze vrijeme u UTC; parser ga sada pretvara u **Europe/Zagreb** prije nego što se određuje
porezna godina i granica od 2 godine (npr. prodaja 31.12. u 23:30 UTC je već 1.1. u Hrvatskoj).
**Podaci spremljeni prije ove izmjene imaju UTC vrijeme.** Deduplikacija po ID-u sprječava da ih ponovni upload
ispravi, pa ih treba obrisati i uvesti ponovno:

```bash
docker exec -it hrtaxapp-postgres psql -U hrtaxapp -d hrtaxapp -c "TRUNCATE transactions;"
```
(prvo napravi `./scripts/backup-db.sh`). Ručne unose i Trading212 PDF treba provjeriti zasebno — PDF parser
vrijeme ne pretvara.

## Na što paziti prije predaje (pravna pitanja za knjigovođu)

- FIFO se za dionice/ETF-ove vodi **po brokeru**; za kripto u jednom zajedničkom poolu. Provjeri što Porezna
  uprava očekuje (pool preko brokera ili odvojeno).
- Stopa (12%), prijenos gubitaka (ne prenose se), tečaj za pretvorbu (HNB na dan kupnje i prodaje, a ne tečaj
  brokera) i rok JOPPD-a (u kodu približno 28.2.) — provjeri aktualne propise.
- Dividende se spremaju ali **ne oporezuju** u ovom obračunu.
- Korporativne akcije (split, spin-off, spajanje) se ne obrađuju; Trading212 ih sada šalje u Karantenu.

## Pokretanje baze (Postgres) — za lokalni razvoj bez punog Docker stacka

```bash
docker compose up -d postgres
```

Diže Postgres na `localhost:5432` (baza `hrtaxapp`, user/pass `hrtaxapp`/`hrtaxapp`).

## Pokretanje backenda

Preduvjeti: Java 21, Maven (repo.maven.apache.org mora biti dostupan
za preuzimanje ovisnosti prvi put), pokrenut Postgres (`docker compose up -d postgres`).

```bash
cd backend
mvn spring-boot:run
```

Backend se diže na `http://localhost:8080`. Testovi (FIFO + porezni
izračun) se pokreću s `mvn test` (ovi testovi ne trebaju bazu - rade
nad in-memory objektima).

## Pokretanje frontenda

Preduvjeti: Node.js 18+.

```bash
cd frontend
npm install
npm run dev
```

Frontend se diže na `http://localhost:5173` i proxya `/api` pozive na
backend (vidi `vite.config.js`).

## Kako radi obračun

1. **Parsiranje** (`Trading212CsvParser`) — čita CSV po nazivu kolone
   (Action, Time (UTC), ISIN, Ticker, No. of shares, Price / share, Total,
   ID...), mapira retke u `Transaction` objekte, filtrira samo BUY/SELL/DIVIDEND.
2. **Perzistencija** (`TransactionRepository`, Postgres) — nove transakcije
   se spremaju, deduplicirane po Trading212 internom ID-u (kolona `ID`) tako
   da ponovni upload istog ili preklapajućeg exporta ne stvara duplikate.
   Obračun se uvijek radi nad CIJELOM poviješću iz baze, ne samo nad zadnjim
   uploadom — dovoljno je s vremena na vrijeme dodati export s novijim
   transakcijama.
3. **FIFO uparivanje** (`FifoMatchingService`) — po BROKERU + ISIN-u (nikad
   preko granice brokera — vidi test `fifoNikadNeUparujePrekoRazlicitihBrokera`),
   prodaje se uparuju s najstarijim još neprodanim kupnjama, uz podršku za
   djelomične prodaje.
4. **Porezni izračun** (`TaxCalculationService`) — svaki uparen par
   (lot) provjerava se je li vlasništvo trajalo > 2 godine (izuzeto od
   poreza). Preostali (oporezivi) dobici/gubici se netiraju unutar iste
   kalendarske godine prodaje, PREKO svih brokera zajedno (to je stvarna
   porezna obveza), a porez (12%) se plaća samo na pozitivnu razliku. Gubici
   se ne prenose u sljedeću godinu. `calculateByBroker()` dodatno raščlanjuje
   isti izračun po brokeru, samo za transparentnost.
5. **JOPPD priprema** (`JoppdExportService`) — slaže pregledni popis
   oporezivih transakcija po godini s ukupnom osnovicom i porezom, kao
   pomoć pri ručnom popunjavanju JOPPD obrasca.

## Export JOPPD pripreme (Excel / PDF)

`GET /api/reports/export/xlsx` i `GET /api/reports/export/pdf` generiraju
downloadable datoteku s istim sažetkom kao `/summary`, nad cijelom spremljenom
poviješću — dugmad "⬇ Excel" / "⬇ PDF" u sekciji "Priprema za JOPPD" u UI-ju.

- **Excel** (`JoppdXlsxExportService`, Apache POI) — jedan list po godini,
  tablica transakcija + ukupna osnovica/porez. Namjerno koristi FIKSNE
  širine stupaca umjesto `autoSizeColumn()`, jer potonji koristi AWT za
  mjerenje fonta što može pući na minimalnim JRE runtime okruženjima bez
  fontconfig-a (npr. naš `eclipse-temurin:*-jre-alpine` Dockerfile).
- **PDF** (`JoppdPdfExportService`, PDFBox) — ručno iscrtana tablica (PDFBox
  nema layout/table API), s automatskim prijelomom stranice. Koristi
  **ugrađeni Liberation Sans font** (`resources/fonts/`, SIL OFL licenca)
  umjesto PDFBox-ovih standardnih Base14 fontova, jer Base14 (Helvetica i
  sl.) NE podržava hrvatske dijakritike (č, ć, đ, š, ž) i bacio bi grešku.

Oba servisa su pažljivo pregledana (usklađenost API poziva, matematika širina
stupaca/margina provjerena), ali **nisu izvršena kroz stvarni Maven build**
u ovom razvojnom okruženju (nema pristupa Maven Central repozitoriju) —
prvi `mvn clean install` + stvarni download PDF-a/Excela je pravi test.

## CORS

Dopuštena podrijetla za API se konfiguriraju centralno u `CorsConfig`
(čita `app.cors.allowed-origins`, podesivo preko env varijable
`APP_CORS_ALLOWED_ORIGINS`), default pokriva Vite dev server (5173),
alternativni dev port (3000) i Docker/Nginx frontend (8081). Ako
frontend hostaš na drugom portu/domeni, dodaj ga na tu listu — inače
ćeš dobiti "Invalid CORS request" iako se poziv šalje prema istom
"vidljivom" originu preko reverse proxyja (preglednik svejedno šalje
`Origin` header koji Spring provjerava neovisno o proxy topologiji).

## HNB FX konverzija (Revolut / IBKR)

`HnbExchangeRateService` pretvara iznose u stranoj valuti (npr. USD) u EUR
koristeći službenu HNB tečajnu listu (`api.hnb.hr/tecajn-eur/v3`, "srednji
tečaj" na dan transakcije, ili najbliži prethodni radni dan ako HNB tog dana
nije objavio tečaj — vikend/praznik). Trading212 ovo ne treba (svoje iznose
već šalje pretvorene).

- **Keširanje**: svaki (valuta, datum) par se sprema u tablicu
  `exchange_rates` nakon prvog dohvata — sljedeći upload s istim datumima ne
  zove HNB ponovno.
- **Neuspjeh**: ako HNB API ne odgovori (mreža, timeout, nepoznata valuta, datum prije 2023.),
  parser postavlja `exchangeRateToEur = 0` kao signal, a `ReportController` takvu transakciju
  (i cijeli par ako je pred-uparen lot) **ne sprema** nego prikaže u Karanteni.
- **NAPOMENA O TESTIRANJU**: HNB API poziv **nije mogao biti testiran uživo**
  iz ovog razvojnog okruženja (nema mrežnog pristupa `api.hnb.hr` odavde).
  Parametri upita (`valuta`, `datum-primjene-od/do`) su bazirani na
  dokumentiranom v2 API-ju uz pretpostavku da v3 zadržava ista imena.
  Kod je namjerno obrambeno napisan (filtrira rezultate na klijentskoj
  strani po valuti i datumu, neovisno o tome je li server ispravno
  primijenio upitne parametre) — ali **prvi stvarni upload Revolut/IBKR
  exporta s ne-EUR transakcijama je pravi test**. Ako se pojavi upozorenje
  "NIJE uspjelo konvertirati", to je znak da treba provjeriti/prilagoditi
  parametre u `HnbExchangeRateService.fetchFromHnb()`.
- `upload` endpoint **nije** `@Transactional` (parsiranje s HNB pozivima ne drži DB transakciju);
  spremanje je jedan `saveAll`.

## Poznata ograničenja / za sljedeće korake

- Revolut i IBKR CSV parseri, i Trading212 PDF parser, su bazirani na
  dokumentiranim/prijašnje viđenim formatima. Trading212 CSV i Revolut CSV
  su verificirani na stvarnim podacima; IBKR CSV i Trading212 PDF još nisu.
- IBKR parser trenutno obrađuje samo dionice/ETF-ove (AssetCategory=STK);
  opcije/futures/forex se preskaču bez upozorenja u odgovoru.
- Nedovoljna povijest kupnji (prodaja bez kupnje u istom poolu) se sada prijavljuje **po brokeru/poolu** u
  Upozorenjima, a ta količina nije u obračunu.
- Trading212 PDF parser očekuje da PDFBox izvuče svaki red tablice kao
  jednu liniju teksta - ako se stvarni PDF layout razlomi drugacije
  (npr. redak prelomljen na vise linija), nece pronaci transakcije.
- JOPPD izlaz je informativni pregled brojki, ne službeni XML format
  za e-Poreznu (za to je potreban FINA certifikat i ovlaštenje).
- Nema autentikacije/multi-user podrške — baza trenutno pretpostavlja
  jednog korisnika (sve transakcije u jednoj tablici, bez `user_id`).
- Karantena (vidi ispod) je EFEMERNA - postoji samo u odgovoru neposredno
  nakon uploada, ne perzistira se u bazi. Ako zatvoriš stranicu prije nego
  je pregledaš, izgubljena je (transakcije koje SU uspješno parsirane ostaju
  spremljene kao i inače - gubi se samo popis "što nije prepoznato").

## Karantena za neprepoznate retke

Umjesto da parseri tiho preskaču retke koje ne razumiju, svaki od njih
(`Trading212CsvParser`, `RevolutCsvParser`, `IbkrCsvParser`,
`Trading212PdfParser`) sad razlikuje dvije vrste "preskočenih" redaka:

- **Namjerno filtrirani** (Deposit/Withdrawal/Interest kod Trading212,
  opcije/futures kod IBKR-a) — legitimno nisu transakcije koje nas zanimaju,
  ne prikazuju se nigdje.
- **Karantena** — redak IZGLEDA kao transakcija (ima datum, ima "Kupi"/
  "Prodaj", ili sličan oblik) ali nedostaje mu obavezno polje ili ne prolazi
  parsiranje. Ovi redci se vraćaju u odgovoru (`skippedRows`) s razlogom, i
  UI ih prikazuje u posebnoj sekciji odmah nakon uploada — da znaš da
  porezna osnovica možda nije potpuna, umjesto da to otkriješ tek kasnije.

Arhitekturno: `StatementParser.parse()` sad vraća `ParseResult` (transakcije
+ karantena) umjesto samog `List<Transaction>`.

## Backup / restore baze

```bash
./scripts/backup-db.sh                    # sprema u backups/hrtaxapp-<datum>.sql
./scripts/restore-db.sh backups/xyz.sql   # vraca (BRISE postojece podatke prije toga)
```

Jednostavan `pg_dump`/`psql` preko `docker exec` na `hrtaxapp-postgres`
kontejner - nema potrebe za dodatnim alatima. `backups/` je u `.gitignore`
(sadrži tvoje stvarne transakcije, ne bi trebao završiti u repozitoriju).

## Testovi

`backend/src/test/java/.../service/FifoAndTaxCalculationTest.java` pokriva:
- osnovni oporezivi dobitak,
- izuzeće nakon 2 godine,
- prijeboj dobitaka i gubitaka unutar godine,
- FIFO redoslijed uparivanja kod više kupnji,
- da neto osnovica nikad nije negativna,
- da FIFO nikad ne upari transakcije preko granice dva brokera.

`backend/src/test/java/.../parser/` pokriva svaki parser zasebno:
- `Trading212CsvParserTest` — parsiranje, filtriranje Deposit-a, karantena
  za redke bez datuma/količine, deduplikacijski `externalId`.
- `RevolutCsvParserTest` — sintetski BUY/SELL par iz "Income from Sells",
  **regresijski test** da dva lota istog ISIN-a prodana istog dana dobiju
  različite `lotGroupKey` vrijednosti (bug koji smo otkrili i riješili),
  parsiranje dividende sa simbolom valute u iznosu ("€0.01"), karantena.
- `IbkrCsvParserTest` — STK kupnja, određivanje smjera iz predznaka
  količine, preskakanje opcija, jasna greška kad nedostaje obavezno polje.

`backend/src/test/java/.../service/HnbExchangeRateServiceTest.java` pokriva
samo keš-scenarije (keš-hit, EUR shortcut) — stvarni HTTP poziv prema HNB-u
NIJE testiran (ovo razvojno okruženje nema pristup `api.hnb.hr`).

Trading212 PDF parser (`Trading212PdfParser`) nema jedinični test — regex
je ručno provjeren protiv stvarne strukture teksta, ali nema realan uzorak
PDF-a za automatizirani test (vidi napomenu o Trading212 PDF-u gore).

## CI/CD

`.github/workflows/ci.yml` se pokreće na svaki push/PR prema `main`:
1. **backend** — `mvn clean verify` (kompajlira + pokreće sve testove)
2. **frontend** — `npm install` + `npm run build`
3. **docker-build** — gradi obje Docker slike kao validaciju (na svaki
   push/PR, ne pushа nikamo)
4. **docker-publish** — SAMO na push u `main`: gradi i pushа obje slike na
   **GitHub Container Registry** (`ghcr.io`), tagirane s `latest` i sa SHA
   commita. Koristi ugrađeni `GITHUB_TOKEN` (ne treba dodatni secret) uz
   `packages: write` dozvolu. Slike su dostupne na:
   - `ghcr.io/<owner>/<repo>-backend:latest`
   - `ghcr.io/<owner>/<repo>-frontend:latest`

   Prvi put kad se ovo pokrene, provjeri na GitHubu (repo → Packages) da su
   paketi objavljeni; možda ih trebaš ručno postaviti na "public" ako želiš
   povlačiti slike bez autentikacije (`docker pull`).
