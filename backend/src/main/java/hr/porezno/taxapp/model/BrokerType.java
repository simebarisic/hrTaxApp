package hr.porezno.taxapp.model;

/**
 * Podrzani izvori izvjestaja. Svaki broker ima svoj format exporta pa
 * zahtijeva zaseban parser (vidi parser.ParserResolver).
 */
public enum BrokerType {
    TRADING212,
    REVOLUT,
    IBKR
}
