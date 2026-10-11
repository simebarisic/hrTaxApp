package hr.porezno.taxapp.model;

/**
 * Podrzani izvori izvjestaja. Svaki broker ima svoj format exporta pa
 * zahtijeva zaseban parser (vidi parser.ParserResolver).
 */
public enum BrokerType {
    TRADING212,
    REVOLUT,
    IBKR,
    /** Strike (Bitcoin). Kripto se vodi u zajednickom FIFO poolu neovisno o platformi. */
    STRIKE;

    /** Kripto platforme dijele jedan FIFO pool po imovini (vidi FifoMatchingService). */
    public boolean isCrypto() {
        return this == STRIKE;
    }
}
