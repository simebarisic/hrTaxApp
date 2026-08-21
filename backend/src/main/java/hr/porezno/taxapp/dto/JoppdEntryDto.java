package hr.porezno.taxapp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Pripremljeni podaci za JEDAN redak JOPPD obrasca (stranica B), za
 * jednu ostvarenu oporezivu prodaju financijske imovine.
 *
 * VAZNA NAPOMENA: ovo NIJE sluzbeni XML format JOPPD-a i aplikacija ga NE
 * podnosi elektronicki u ePoreznu (za to je potreban FINA certifikat i
 * ovlastenje). Ovo je "cheat sheet" - pripremljeni brojevi koje korisnik
 * prepisuje/unosi u sluzbeni obrazac ili predaje svom knjigovodi/poreznom
 * savjetniku. Sifre i rokovi (npr. oznaka primitka za dohodak od
 * kapitala/kapitalnih dobitaka) treba potvrditi s aktualnom Naredbom o
 * strukturi i sadrzaju JOPPD obrasca Porezne uprave prije predaje.
 */
public record JoppdEntryDto(
        LocalDate sellDate,
        String isin,
        String ticker,
        String broker,
        BigDecimal quantity,
        BigDecimal proceedsEur,
        BigDecimal costBasisEur,
        BigDecimal gainEur,
        String napomena
) {}
