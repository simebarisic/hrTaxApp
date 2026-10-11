package hr.porezno.taxapp.controller;

import hr.porezno.taxapp.dto.ManualTransactionRequest;
import hr.porezno.taxapp.dto.TaxReportResponse;
import hr.porezno.taxapp.model.BrokerType;
import hr.porezno.taxapp.model.Transaction;
import hr.porezno.taxapp.model.TransactionType;
import jakarta.validation.Valid;
import java.util.UUID;
import hr.porezno.taxapp.parser.ParseResult;
import hr.porezno.taxapp.parser.ParserResolver;
import hr.porezno.taxapp.parser.StatementParser;
import hr.porezno.taxapp.repository.TransactionRepository;
import hr.porezno.taxapp.service.TaxReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final TaxReportService taxReportService;

    public ReportController(ParserResolver parserResolver,
                             TransactionRepository transactionRepository,
                             TaxReportService taxReportService) {
        this.parserResolver = parserResolver;
        this.transactionRepository = transactionRepository;
        this.taxReportService = taxReportService;
    }

    /**
     * Uploada novi izvjestaj, sprema samo transakcije koje jos ne postoje u bazi
     * (dedup po externalId), a zatim obracun radi nad CIJELOM povijescu iz baze.
     *
     * Namjerno NIJE @Transactional: parsiranje ukljucuje HTTP pozive prema HNB-u
     * i ne smije drzati DB transakciju otvorenom; spremanje (saveAll) je samo po
     * sebi atomarno.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam("broker") BrokerType broker) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Datoteka je prazna."));
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";

        try {
            StatementParser parser = parserResolver.resolve(broker, filename);
            ParseResult parseResult = parser.parse(file.getInputStream());

            List<ParseResult.SkippedRow> skipped = new ArrayList<>(parseResult.getSkippedRows());
            List<Transaction> parsed = rejectUnconverted(parseResult.getTransactions(), skipped);

            if (parsed.isEmpty()) {
                String hint = skipped.isEmpty()
                        ? ""
                        : " (" + skipped.size() + " redaka je izgledalo kao transakcija ali nije prihvaceno - vidi razloge u karanteni.)";
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Nije prepoznata nijedna valjana BUY/SELL/DIVIDEND transakcija u datoteci. "
                                + "Provjerite je li ovo ispravan export za odabranog brokera (" + broker + ")." + hint
                ));
            }

            int newlyImported = persistNewOnly(parsed);
            return buildResponse(parsed.size(), newlyImported, skipped);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Greska pri citanju datoteke: " + e.getMessage()));
        }
    }

    /** Rucni unos jedne kupnje/prodaje (npr. BTC kupljen izvan Strikea). */
    @PostMapping("/transactions")
    public ResponseEntity<?> addManual(@Valid @RequestBody ManualTransactionRequest req) {
        if (req.type() != TransactionType.BUY && req.type() != TransactionType.SELL) {
            return ResponseEntity.badRequest().body(Map.of("error", "Rucni unos podrzava samo BUY ili SELL."));
        }
        if (req.dateTime().isAfter(LocalDateTime.now().plusDays(1))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Datum transakcije ne smije biti u buducnosti."));
        }
        String ticker = req.ticker().trim().toUpperCase();
        BigDecimal price = req.totalEur().divide(req.quantity(), 8, java.math.RoundingMode.HALF_UP);

        transactionRepository.save(Transaction.builder()
                .externalId("MANUAL:" + UUID.randomUUID())
                .broker(req.broker())
                .dateTime(req.dateTime())
                .type(req.type())
                .isin(req.isin() == null || req.isin().isBlank() ? null : req.isin().trim())
                .ticker(ticker)
                .name(ticker)
                .quantity(req.quantity())
                .pricePerShare(price)
                .currency("EUR")
                .exchangeRateToEur(BigDecimal.ONE)
                .feesEur(BigDecimal.ZERO)
                .totalEur(req.totalEur())
                .build());
        return buildResponse(1, 1, List.of());
    }

    /** Brise JEDNU transakciju (npr. pogresan rucni unos). */
    @DeleteMapping("/transactions/{id}")
    public ResponseEntity<?> deleteTransaction(@PathVariable Long id) {
        if (!transactionRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        transactionRepository.deleteById(id);
        return buildResponse(0, 0, List.of());
    }

    /** Brise SVE spremljene transakcije (svih brokera) - za ponovni uvoz od nule. Tecajevi (kes) ostaju. */
    @DeleteMapping("/transactions")
    public ResponseEntity<?> deleteAllTransactions() {
        transactionRepository.deleteAllInBatch();
        return buildResponse(0, 0, List.of());
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary() {
        return buildResponse(0, 0, List.of());
    }

    /**
     * Transakcije kojima HNB konverzija nije uspjela (exchangeRateToEur == 0, iznos je SIROV)
     * se NE spremaju - umjesto da tiho iskrive poreznu osnovicu idu u karantenu. Ako je
     * transakcija dio pred-uparenog lota (lotGroupKey), odbacuje se cijeli par.
     */
    private List<Transaction> rejectUnconverted(List<Transaction> parsed, List<ParseResult.SkippedRow> skipped) {
        Set<String> badGroups = new HashSet<>();
        for (Transaction tx : parsed) {
            if (isUnconverted(tx) && tx.getLotGroupKey() != null) badGroups.add(tx.getLotGroupKey());
        }
        List<Transaction> ok = new ArrayList<>();
        for (Transaction tx : parsed) {
            boolean bad = isUnconverted(tx) || (tx.getLotGroupKey() != null && badGroups.contains(tx.getLotGroupKey()));
            if (!bad) {
                ok.add(tx);
                continue;
            }
            skipped.add(new ParseResult.SkippedRow(tx.getSourceRowIndex(),
                    tx.getType() + " " + tx.getTicker() + " " + tx.getDateTime() + " " + tx.getCurrency(),
                    "HNB konverzija " + tx.getCurrency() + " -> EUR nije uspjela (API nedostupan, nepoznat tecaj, "
                            + "ili datum prije 2023. kad HNB EUR-lista ne postoji). Transakcija NIJE spremljena - "
                            + "ponovi upload kasnije ili je unesi rucno u EUR."));
        }
        return ok;
    }

    private boolean isUnconverted(Transaction tx) {
        return tx.getExchangeRateToEur() != null && tx.getExchangeRateToEur().signum() == 0;
    }

    private int persistNewOnly(List<Transaction> parsed) {
        Set<String> ids = parsed.stream().map(Transaction::getExternalId).collect(Collectors.toSet());
        Set<String> existing = transactionRepository.findExistingExternalIds(ids);

        // Dedup i prema bazi i unutar samog uploada (preklapajuci periodi) - prvo pojavljivanje pobjeduje.
        Set<String> seen = new HashSet<>();
        List<Transaction> toSave = new ArrayList<>();
        for (Transaction tx : parsed) {
            if (!existing.contains(tx.getExternalId()) && seen.add(tx.getExternalId())) {
                toSave.add(tx);
            }
        }
        transactionRepository.saveAll(toSave);
        return toSave.size();
    }

    private ResponseEntity<?> buildResponse(int parsedThisUploadCount, int newlyImported, List<ParseResult.SkippedRow> skippedRows) {
        TaxReportService.Report r = taxReportService.buildFromFullHistory();
        return ResponseEntity.ok(new TaxReportResponse(
                parsedThisUploadCount,
                newlyImported,
                r.transactions().size(),
                r.transactions(),
                r.matchedLots(),
                r.taxYearSummaries(),
                r.taxYearSummariesByBroker(),
                r.joppdSummaries(),
                r.warnings(),
                skippedRows
        ));
    }
}
