package hr.porezno.taxapp.controller;

import hr.porezno.taxapp.dto.JoppdYearSummaryDto;
import hr.porezno.taxapp.dto.TaxReportResponse;
import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.MatchedLot;
import hr.porezno.taxapp.model.TaxYearSummary;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.model.TransactionType;
import hr.porezno.taxapp.parser.ParseResult;
import hr.porezno.taxapp.parser.ParserResolver;
import hr.porezno.taxapp.parser.StatementParser;
import hr.porezno.taxapp.repository.TransactionRepository;
import hr.porezno.taxapp.service.FifoMatchingService;
import hr.porezno.taxapp.service.JoppdExportService;
import hr.porezno.taxapp.service.TaxCalculationService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ParserResolver parserResolver;
    private final TransactionRepository transactionRepository;
    private final FifoMatchingService fifoMatchingService;
    private final TaxCalculationService taxCalculationService;
    private final JoppdExportService joppdExportService;

    public ReportController(ParserResolver parserResolver,
                             TransactionRepository transactionRepository,
                             FifoMatchingService fifoMatchingService,
                             TaxCalculationService taxCalculationService,
                             JoppdExportService joppdExportService) {
        this.parserResolver = parserResolver;
        this.transactionRepository = transactionRepository;
        this.fifoMatchingService = fifoMatchingService;
        this.taxCalculationService = taxCalculationService;
        this.joppdExportService = joppdExportService;
    }

    /**
     * Uploada novi izvjestaj (CSV ili PDF, ovisno o brokeru), sprema samo
     * transakcije koje jos ne postoje u bazi (deduplikacija po externalId -
     * vidi Transaction.externalId, prefiksiran imenom brokera), a zatim
     * obracun radi nad CIJELOM poviješcu iz baze (ne samo nad ovim uploadom),
     * jer FIFO metoda zahtijeva puni redoslijed kupnji/prodaja - PO ISIN-u,
     * neovisno o tome kod kojeg je brokera pojedina transakcija nastala
     * (isti vrijednosni papir se za porezne svrhe promatra jedinstveno).
     */
    @PostMapping("/upload")
    @Transactional
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam("broker") BrokerType broker) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Datoteka je prazna."));
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";

        try {
            StatementParser parser = parserResolver.resolve(broker, filename);
            ParseResult parseResult = parser.parse(file.getInputStream());
            List<Transaction> parsed = parseResult.getTransactions();

            if (parsed.isEmpty()) {
                String hint = parseResult.getSkippedRows().isEmpty()
                        ? ""
                        : " (" + parseResult.getSkippedRows().size() + " redaka je izgledalo kao transakcija ali nije prepoznato - provjerite je li ovo ispravan tip izvjestaja.)";
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Nije prepoznata nijedna BUY/SELL/DIVIDEND transakcija u datoteci. "
                                + "Provjerite je li ovo ispravan export za odabranog brokera (" + broker + ")." + hint
                ));
            }

            int newlyImported = persistNewOnly(parsed);

            return buildResponseFromFullHistory(parsed.size(), newlyImported, parseResult.getSkippedRows());

        } catch (IllegalArgumentException e) {
            // Nepodrzana kombinacija brokera/formata, ili IBKR CSV-u nedostaje
            // obavezno polje - poruka je vec smislena za prikaz korisniku.
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Greska pri citanju datoteke: " + e.getMessage()));
        }
    }

    /**
     * Ponovno izracunava obracun nad vec spremljenom poviješcu, bez potrebe
     * za novim uploadom - korisno da frontend odmah pri otvaranju prikaze
     * zadnje stanje. Karantena (skippedRows) je EFEMERNA - postoji samo u
     * odgovoru neposredno nakon uploada, ne perzistira se (nevaljani redci se
     * ne spremaju u bazu), pa se ovdje uvijek vraca prazna lista.
     */
    @GetMapping("/summary")
    public ResponseEntity<?> summary() {
        return buildResponseFromFullHistory(0, 0, List.of());
    }

    private int persistNewOnly(List<Transaction> parsed) {
        Set<String> ids = parsed.stream().map(Transaction::getExternalId).collect(Collectors.toSet());
        Set<String> existing = transactionRepository.findExistingExternalIds(ids);

        List<Transaction> toSave = parsed.stream()
                .filter(tx -> !existing.contains(tx.getExternalId()))
                .toList();

        // Unutar samog uploada datoteka takoder moze imati duplicirane retke
        // (npr. ako korisnik izveze dva preklapajuca perioda odjednom) -
        // cuvamo samo prvo pojavljivanje svakog externalId-a.
        Set<String> seen = new HashSet<>();
        List<Transaction> deduped = new ArrayList<>();
        for (Transaction tx : toSave) {
            if (seen.add(tx.getExternalId())) {
                deduped.add(tx);
            }
        }

        transactionRepository.saveAll(deduped);
        return deduped.size();
    }

    private ResponseEntity<?> buildResponseFromFullHistory(int parsedThisUploadCount, int newlyImported, List<ParseResult.SkippedRow> skippedRows) {
        List<Transaction> allTransactions = transactionRepository.findAllByOrderByDateTimeAsc();

        List<MatchedLot> matchedLots = fifoMatchingService.match(allTransactions);
        List<TaxYearSummary> taxYearSummaries = taxCalculationService.calculate(matchedLots);
        var taxYearSummariesByBroker = taxCalculationService.calculateByBroker(matchedLots);
        List<JoppdYearSummaryDto> joppdSummaries = joppdExportService.buildSummaries(taxYearSummaries);
        List<String> warnings = buildWarnings(allTransactions);

        TaxReportResponse response = new TaxReportResponse(
                parsedThisUploadCount,
                newlyImported,
                allTransactions.size(),
                allTransactions,
                matchedLots,
                taxYearSummaries,
                taxYearSummariesByBroker,
                joppdSummaries,
                warnings,
                skippedRows
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Osnovne provjere konzistentnosti podataka - ne blokiraju obracun, ali
     * upozoravaju korisnika da rezultat moze biti nepotpun (npr. nedostaje
     * povijest kupnji jer nijedan dosad uploadani export ne pokriva cijelo
     * razdoblje ulaganja).
     */
    private List<String> buildWarnings(List<Transaction> transactions) {
        List<String> warnings = new ArrayList<>();

        Map<String, BigDecimal> boughtByIsin = new HashMap<>();
        Map<String, BigDecimal> soldByIsin = new HashMap<>();

        for (Transaction tx : transactions) {
            String key = tx.getIsin() != null ? tx.getIsin() : tx.getTicker();
            if (key == null) continue;
            BigDecimal qty = tx.getQuantity() != null ? tx.getQuantity() : BigDecimal.ZERO;
            if (tx.getType() == TransactionType.BUY) {
                boughtByIsin.merge(key, qty, BigDecimal::add);
            } else if (tx.getType() == TransactionType.SELL) {
                soldByIsin.merge(key, qty, BigDecimal::add);
            }
        }

        for (Map.Entry<String, BigDecimal> entry : soldByIsin.entrySet()) {
            BigDecimal bought = boughtByIsin.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (entry.getValue().compareTo(bought) > 0) {
                warnings.add("Za " + entry.getKey() + " je prodano vise jedinica (" + entry.getValue()
                        + ") nego sto je evidentirano kupnji (" + bought
                        + ") u dosad uploadanim datotekama - vjerojatno nedostaje dio povijesti kupnji. Uploadajte export od pocetka trgovanja za tocan obracun.");
            }
        }

        // exchangeRateToEur == ZERO je sentinel koji parseri postavljaju kad HNB
        // konverzija NIJE uspjela (HNB nedostupan, mrezna greska i sl.) - u tom
        // slucaju je totalEur zapravo SIROV, nekonvertirani iznos. Za EUR
        // transakcije (i Trading212, koji ionako salje vec pretvoreno) ovo se
        // ne dogada jer se konverzija tamo ne ni pokusava (nije potrebna).
        long failedConversionCount = transactions.stream()
                .filter(tx -> tx.getExchangeRateToEur() != null && tx.getExchangeRateToEur().signum() == 0)
                .count();
        if (failedConversionCount > 0) {
            Set<String> affectedCurrencies = transactions.stream()
                    .filter(tx -> tx.getExchangeRateToEur() != null && tx.getExchangeRateToEur().signum() == 0)
                    .map(Transaction::getCurrency)
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
            warnings.add(failedConversionCount + " transakcija u valutama " + String.join(", ", affectedCurrencies)
                    + " NIJE uspjelo konvertirati u EUR preko HNB tecajnice (API nedostupan ili mrezna greska u "
                    + "trenutku uploada) - za te transakcije je koristen SIROV (nekonvertiran) iznos, sto ce dati "
                    + "netocnu poreznu osnovicu. Pokusaj ponovno uploadati datoteku, ili rucno provjeri te iznose.");
        }

        return warnings;
    }
}
