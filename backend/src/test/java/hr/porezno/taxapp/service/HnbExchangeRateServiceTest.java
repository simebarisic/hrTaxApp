package hr.porezno.taxapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import hr.porezno.taxapp.model.ExchangeRate;
import hr.porezno.taxapp.repository.ExchangeRateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Testira samo scenarije koji NE zahtijevaju stvaran mrezni poziv prema HNB
 * (kes-hit, EUR shortcut) - ovo razvojno okruzenje nema pristup api.hnb.hr,
 * pa stvarni HTTP fetch nije pokriven testom (vidi HnbExchangeRateService
 * javadoc o granicama testiranja).
 */
class HnbExchangeRateServiceTest {

    private final ExchangeRateRepository repository = Mockito.mock(ExchangeRateRepository.class);
    private final HnbExchangeRateService service = new HnbExchangeRateService(repository, new ObjectMapper());

    @Test
    void eurValutaSePreskacePotpunoBezPoziva() {
        BigDecimal result = service.convertToEur(new BigDecimal("100"), "EUR", LocalDate.of(2026, 1, 15));

        assertEquals(0, new BigDecimal("100").compareTo(result));
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void kesHitVracaSpremljeniTecajBezMreznogPoziva() {
        LocalDate date = LocalDate.of(2026, 1, 15);
        when(repository.findByCurrencyAndRateDate("USD", date))
                .thenReturn(Optional.of(new ExchangeRate("USD", date, new BigDecimal("1.08"))));

        BigDecimal rate = service.getRate("USD", date);

        assertEquals(0, new BigDecimal("1.08").compareTo(rate));
        Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void convertToEurDijeliIznosSTecajemIzKesa() {
        LocalDate date = LocalDate.of(2026, 1, 15);
        when(repository.findByCurrencyAndRateDate("USD", date))
                .thenReturn(Optional.of(new ExchangeRate("USD", date, new BigDecimal("1.08"))));

        BigDecimal result = service.convertToEur(new BigDecimal("108"), "USD", date);

        assertEquals(0, new BigDecimal("100.000000").compareTo(result));
    }

    @Test
    void nullIznosVracaNull() {
        assertNull(service.convertToEur(null, "USD", LocalDate.now()));
    }
}
