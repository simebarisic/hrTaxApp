package hr.porezno.taxapp.parser;

import java.io.IOException;
import java.io.InputStream;

/**
 * Zajednicko sucelje za sve parsere izvjestaja (jedan po brokeru i formatu).
 * Implementacije NE smiju zatvarati predani InputStream (poziva se izvana).
 */
public interface StatementParser {
    ParseResult parse(InputStream inputStream) throws IOException;
}
