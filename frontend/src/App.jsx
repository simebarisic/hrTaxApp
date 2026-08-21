import { useEffect, useState } from 'react'

const EUR = new Intl.NumberFormat('hr-HR', { style: 'currency', currency: 'EUR' })
const DATE = new Intl.DateTimeFormat('hr-HR', { day: '2-digit', month: '2-digit', year: 'numeric' })

const BROKERS = [
  { value: 'TRADING212', label: 'Trading212', accept: '.csv,.pdf', hint: 'CSV (Menu → History → Export) ili PDF izvod aktivnosti (eksperimentalno).' },
  { value: 'REVOLUT', label: 'Revolut', accept: '.csv', hint: 'CSV export iz Trading → Statements → Tax Report.' },
  { value: 'IBKR', label: 'Interactive Brokers', accept: '.csv', hint: 'CSV iz Flex Query (Reports → Flex Queries → Trades). Neverificirano na stvarnim podacima.' },
]

function fmtEur(v) {
  if (v === null || v === undefined) return '-'
  return EUR.format(Number(v))
}

function fmtDate(v) {
  if (!v) return '-'
  return DATE.format(new Date(v))
}

export default function App() {
  const [file, setFile] = useState(null)
  const [broker, setBroker] = useState('TRADING212')
  const [loading, setLoading] = useState(false)
  const [initialLoading, setInitialLoading] = useState(true)
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null)
  const [showTransactions, setShowTransactions] = useState(false)
  const [brokerFilter, setBrokerFilter] = useState('ALL')

  // Pri otvaranju aplikacije odmah ucitaj zadnje stanje iz baze (ako postoji),
  // bez potrebe za novim uploadom.
  useEffect(() => {
    fetch('/api/reports/summary')
      .then(res => res.ok ? res.json() : null)
      .then(data => {
        if (data && data.totalStoredCount > 0) {
          setResult(data)
        }
      })
      .catch(() => { /* backend jos nije dostupan ili nema podataka - tiho preskoci */ })
      .finally(() => setInitialLoading(false))
  }, [])

  async function handleUpload(e) {
    e.preventDefault()
    if (!file) return
    setLoading(true)
    setError(null)
    setResult(null)

    const formData = new FormData()
    formData.append('file', file)
    formData.append('broker', broker)

    try {
      const res = await fetch('/api/reports/upload', {
        method: 'POST',
        body: formData
      })
      const data = await res.json()
      if (!res.ok) {
        setError(data.error || 'Greška prilikom obrade datoteke.')
      } else {
        setResult(data)
      }
    } catch (err) {
      setError('Ne mogu se spojiti na backend (je li Spring Boot pokrenut na :8080?): ' + err.message)
    } finally {
      setLoading(false)
    }
  }

  const presentBrokers = result ? [...new Set(result.transactions.map(t => t.broker))] : []
  const filteredTransactions = result
    ? (brokerFilter === 'ALL' ? result.transactions : result.transactions.filter(t => t.broker === brokerFilter))
    : []

  return (
    <div className="page">
      <header>
        <h1>Porez na kapitalnu dobit</h1>
        <p className="subtitle">
          Uploadaj Trading212 CSV export (Menu → History → Export) i dobij FIFO obračun,
          poreznu obvezu (12%, izuzeće nakon 2 godine) i pripremljene podatke za JOPPD.
        </p>
      </header>

      <section className="card">
        <form onSubmit={handleUpload}>
          <select value={broker} onChange={(e) => { setBroker(e.target.value); setFile(null) }}>
            {BROKERS.map(b => <option key={b.value} value={b.value}>{b.label}</option>)}
          </select>
          <input
            key={broker}
            type="file"
            accept={BROKERS.find(b => b.value === broker)?.accept}
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
          <button type="submit" disabled={!file || loading}>
            {loading ? 'Obrađujem...' : 'Izračunaj porez'}
          </button>
        </form>
        <p className="hint">{BROKERS.find(b => b.value === broker)?.hint}</p>
        <p className="hint">
          Napomena: aplikacija ne šalje ništa u ePoreznu automatski — samo priprema brojke
          za JOPPD koje sam prepisuješ ili predaješ knjigovođi/poreznom savjetniku. Podaci
          se trajno spremaju, pa sljedeći put ne moraš uploadati cijelu povijest — samo
          dodaj export s novijim transakcijama.
        </p>
      </section>

      {initialLoading && <p className="hint">Učitavam spremljeno stanje…</p>}

      {error && <div className="card error">{error}</div>}

      {result && result.parsedTransactionCount > 0 && (
        <div className="card info">
          Uvezeno {result.newlyImportedCount} novih transakcija (od {result.parsedTransactionCount} u
          datoteci — ostatak je već bio spremljen). Ukupno u bazi: {result.totalStoredCount}.
        </div>
      )}

      {result && (
        <>
          {result.warnings?.length > 0 && (
            <section className="card warning">
              <h3>Upozorenja</h3>
              <ul>
                {result.warnings.map((w, i) => <li key={i}>{w}</li>)}
              </ul>
            </section>
          )}

          {result.skippedRows?.length > 0 && (
            <section className="card warning">
              <h3>Karantena — {result.skippedRows.length} redaka nije prepoznato</h3>
              <p className="hint">
                Ovi redci iz zadnjeg uploada izgledaju kao transakcije, ali parser ih nije
                uspio razumjeti — nisu uključeni u obračun. Provjeri ih ručno; ako je riječ o
                stvarnoj kupnji/prodaji, porezna osnovica trenutno je nepotpuna.
              </p>
              <table className="data-table">
                <thead>
                  <tr><th>Redak #</th><th>Sadržaj</th><th>Razlog</th></tr>
                </thead>
                <tbody>
                  {result.skippedRows.map((s, i) => (
                    <tr key={i}>
                      <td>{s.rowIndex}</td>
                      <td>{s.rawContent}</td>
                      <td>{s.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}

          <section className="card">
            <h2>Porezni sažetak po godinama — ukupno (za JOPPD)</h2>
            <p className="hint">
              Ovo je stvarna porezna obveza: zbroj svih dobitaka/gubitaka po godini PREKO svih
              brokera zajedno — Porezna uprava ne razlikuje kod kojeg je brokera dobitak nastao.
            </p>
            {result.taxYearSummaries.length === 0 && <p>Nema realiziranih prodaja u učitanoj datoteci.</p>}
            <div className="year-grid">
              {result.taxYearSummaries.map((y) => (
                <div className="year-box" key={y.year}>
                  <h3>{y.year}.</h3>
                  <table>
                    <tbody>
                      <tr><td>Oporezivi dobici</td><td>{fmtEur(y.totalTaxableGainsEur)}</td></tr>
                      <tr><td>Oporezivi gubici</td><td>{fmtEur(y.totalTaxableLossesEur)}</td></tr>
                      <tr><td>Neto porezna osnovica</td><td>{fmtEur(y.netTaxableBaseEur)}</td></tr>
                      <tr className="highlight"><td>Porez (12%)</td><td>{fmtEur(y.taxDueEur)}</td></tr>
                      <tr><td>Neoporezivi dobitak (&gt;2 god.)</td><td>{fmtEur(y.totalExemptGainEur)}</td></tr>
                    </tbody>
                  </table>
                </div>
              ))}
            </div>
          </section>

          {result.taxYearSummariesByBroker && Object.keys(result.taxYearSummariesByBroker).length > 1 && (
            <section className="card">
              <h2>Raščlamba po brokeru (uvid)</h2>
              <p className="hint">
                FIFO se primjenjuje ODVOJENO po brokeru — kupnja kod jednog brokera se nikad ne
                upari s prodajom kod drugog. Ovo je samo prikaz odakle dolazi koji dio dobiti;
                stvarna porezna obveza je zbroj iznad, ne zbroj ovih brojki (ne zbrajaj ih ručno —
                mogu se preklapati po definiciji izuzeća unutar iste godine).
              </p>
              {Object.entries(result.taxYearSummariesByBroker).map(([brokerName, summaries]) => (
                <div key={brokerName} className="broker-breakdown-box">
                  <h4>{brokerName}</h4>
                  <div className="year-grid">
                    {summaries.map((y) => (
                      <div className="year-box" key={y.year}>
                        <h3>{y.year}.</h3>
                        <table>
                          <tbody>
                            <tr><td>Oporeziva osnovica</td><td>{fmtEur(y.netTaxableBaseEur)}</td></tr>
                            <tr><td>Porez (12%)</td><td>{fmtEur(y.taxDueEur)}</td></tr>
                          </tbody>
                        </table>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </section>
          )}

          <section className="card">
            <div className="transactions-header">
              <h2>Priprema za JOPPD</h2>
              <div className="export-buttons">
                <a className="chip" href="/api/reports/export/xlsx">⬇ Excel</a>
                <a className="chip" href="/api/reports/export/pdf">⬇ PDF</a>
              </div>
            </div>
            {result.joppdSummaries.map((j) => (
              <div key={j.year} className="joppd-box">
                <h3>{j.year}. (okvirni rok: {fmtDate(j.rokPlacanja)})</h3>
                <p className="hint">{j.napomena}</p>
                {j.entries.length > 0 && (
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Datum prodaje</th><th>Broker</th><th>Ticker</th><th>ISIN</th><th>Kol.</th>
                        <th>Nabavna vrij.</th><th>Prodajna vrij.</th><th>Dobit/gubitak</th>
                      </tr>
                    </thead>
                    <tbody>
                      {j.entries.map((e, i) => (
                        <tr key={i}>
                          <td>{fmtDate(e.sellDate)}</td>
                          <td>{e.broker}</td>
                          <td>{e.ticker}</td>
                          <td>{e.isin}</td>
                          <td>{e.quantity}</td>
                          <td>{fmtEur(e.costBasisEur)}</td>
                          <td>{fmtEur(e.proceedsEur)}</td>
                          <td className={Number(e.gainEur) < 0 ? 'neg' : 'pos'}>{fmtEur(e.gainEur)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                <p className="totals">
                  Ukupna osnovica: <strong>{fmtEur(j.ukupnaOsnovicaEur)}</strong> &nbsp;|&nbsp;
                  Porez: <strong>{fmtEur(j.ukupanPorezEur)}</strong>
                </p>
              </div>
            ))}
          </section>

          <section className="card">
            <div className="transactions-header">
              <button className="link-btn" onClick={() => setShowTransactions(s => !s)}>
                {showTransactions ? 'Sakrij' : 'Prikaži'} sve učitane transakcije ({result.totalStoredCount})
              </button>
              {presentBrokers.length > 1 && (
                <div className="broker-filter">
                  <button
                    className={brokerFilter === 'ALL' ? 'chip active' : 'chip'}
                    onClick={() => setBrokerFilter('ALL')}
                  >Svi</button>
                  {presentBrokers.map(b => (
                    <button
                      key={b}
                      className={brokerFilter === b ? 'chip active' : 'chip'}
                      onClick={() => setBrokerFilter(b)}
                    >{b}</button>
                  ))}
                </div>
              )}
            </div>
            {showTransactions && (
              <table className="data-table">
                <thead>
                  <tr><th>Datum</th><th>Broker</th><th>Tip</th><th>Ticker</th><th>Kol.</th><th>Cijena</th><th>Ukupno (EUR)</th></tr>
                </thead>
                <tbody>
                  {filteredTransactions.map((t, i) => (
                    <tr key={i}>
                      <td>{fmtDate(t.dateTime)}</td>
                      <td>{t.broker}</td>
                      <td>{t.type}</td>
                      <td>{t.ticker}</td>
                      <td>{t.quantity}</td>
                      <td>{t.pricePerShare} {t.currency}</td>
                      <td>{fmtEur(t.totalEur)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}
    </div>
  )
}
