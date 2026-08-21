package hr.porezno.taxapp.parser;

import hr.porezno.taxapp.model.Transaction;

import java.util.ArrayList;
import java.util.List;

/**
 * Rezultat parsiranja jedne datoteke: uspjesno parsirane transakcije PLUS
 * "karantena" - redci koje parser nije uspio razumjeti (ili im nedostaje
 * obavezno polje), s razlogom. Za razliku od namjerno filtriranih redaka
 * (npr. Deposit/Withdrawal/Interest kod Trading212 - to legitimno nisu
 * transakcije koje nas zanimaju), karantena su redci koji IZGLEDAJU kao
 * transakcija ali nesto s njima nije u redu - korisnik ih treba vidjeti da
 * zna da porezna osnovica moze biti nepotpuna.
 */
public class ParseResult {

    private final List<Transaction> transactions;
    private final List<SkippedRow> skippedRows;

    public ParseResult() {
        this.transactions = new ArrayList<>();
        this.skippedRows = new ArrayList<>();
    }

    public void addTransaction(Transaction tx) {
        transactions.add(tx);
    }

    public void addSkipped(int rowIndex, String rawContent, String reason) {
        skippedRows.add(new SkippedRow(rowIndex, rawContent, reason));
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public List<SkippedRow> getSkippedRows() {
        return skippedRows;
    }

    public record SkippedRow(int rowIndex, String rawContent, String reason) {}
}
