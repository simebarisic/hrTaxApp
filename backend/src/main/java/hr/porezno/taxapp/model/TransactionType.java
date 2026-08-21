package hr.porezno.taxapp.model;

/**
 * Vrsta transakcije relevantna za obracun poreza na kapitalnu dobit
 * (dividende i kamate se evidentiraju, ali ne ulaze u FIFO uparivanje
 * jer se oporezuju po drugim pravilima, bez pravila "2 godine").
 */
public enum TransactionType {
    BUY,
    SELL,
    DIVIDEND,
    OTHER
}
