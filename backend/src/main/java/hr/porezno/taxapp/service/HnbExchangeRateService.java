package hr.porezno.taxapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import hr.porezno.taxapp.model.ExchangeRate;
import hr.porezno.taxapp.repository.ExchangeRateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

/**
 * Konverzija iznosa u stranoj valuti u EUR koristeci sluzbenu HNB tecajnu
 * listu (https://api.hnb.hr/tecajn-eur/v3, "srednji tecaj").
 *
 * Koristi se za Revolut/IBKR transakcije koje dolaze u izvornoj valuti (npr.
 * USD) - za razliku od Trading212 koji svoje iznose vec salje pretvorene u
 * EUR (tamo ovaj servis nije potreban).
 *
 * NAPOMENA O TESTIRANJU: HNB API poziv NIJE mogao biti testiran uzivo iz
 * razvojnog okruzenja u kojem je ovaj kod pisan (bez pristupa vanjskoj mrezi
 * prema api.hnb.hr) - parametri upita (valuta, datum-primjene-od/do) su
 * bazirani na dokumentiranom v2 API-ju uz pretpostavku da v3 zadrzava ista
 * imena parametara. Kod je NAMJERNO obrambeno napisan (filtrira rezultate na
 * klijentskoj strani po valuti i datumu, neovisno o tome je li server
 * ispravno primijenio upitne parametre) da bi bio otporniji na eventualne
 * razlike - ali prvi stvarni poziv u produkciji (npr. upload Revolut/IBKR
 * exporta s USD transakcijama) je pravi test.
 */
@Service
public class HnbExchangeRateService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    // HNB ne objavljuje tecaj vikendom/praznikom - gledamo unazad do N dana
    // da nademo najbliži prethodni radni dan ako trazeni datum nema tecaj.
    private static final int LOOKBACK_DAYS = 10;

    private final ExchangeRateRepository repository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public HnbExchangeRateService(ExchangeRateRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Pretvara iznos u EUR po HNB srednjem tecaju na dan "date" (ili najbliži
     * prethodni radni dan). Vraca null ako konverzija nije uspjela (HNB
     * nedostupan, nepoznata valuta, mrezna greska) - pozivatelj tada treba
     * fallback na neconvertirani iznos i oznaciti transakciju kao takvu.
     */
    public BigDecimal convertToEur(BigDecimal amount, String currencyCode, LocalDate date) {
        if (amount == null) return null;
        if (currencyCode == null || "EUR".equalsIgnoreCase(currencyCode)) return amount;
        if (date == null) return null;

        BigDecimal rate = getRate(currencyCode.toUpperCase(), date);
        if (rate == null || rate.signum() <= 0) return null;

        return amount.divide(rate, 6, RoundingMode.HALF_UP);
    }

    /**
     * Vraca koliko jedinica "currencyCode" vrijedi 1 EUR, na dan "date" (ili
     * najbliži prethodni radni dan unutar LOOKBACK_DAYS). Null ako nije
     * pronadeno (nepoznata valuta ili HNB nedostupan).
     */
    public BigDecimal getRate(String currencyCode, LocalDate date) {
        Optional<ExchangeRate> cached = repository.findByCurrencyAndRateDate(currencyCode, date);
        if (cached.isPresent()) {
            return cached.get().getEurRate();
        }

        BigDecimal fetched = fetchFromHnb(currencyCode, date);
        if (fetched != null) {
            try {
                repository.save(new ExchangeRate(currencyCode, date, fetched));
            } catch (Exception ignored) {
                // Race s drugim paralelnim upitom koji je u medjuvremenu spremio
                // isti (currency, date) par - nije fatalno, samo se ne duplira kes.
            }
        }
        return fetched;
    }

    private BigDecimal fetchFromHnb(String currencyCode, LocalDate date) {
        LocalDate from = date.minusDays(LOOKBACK_DAYS);
        String url = "https://api.hnb.hr/tecajn-eur/v3?valuta=" + currencyCode
                + "&datum-primjene-od=" + from.format(ISO)
                + "&datum-primjene-do=" + date.format(ISO);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return null;
            }

            HnbRateEntry[] entries = objectMapper.readValue(response.body(), HnbRateEntry[].class);

            // Obrambeno filtriranje na klijentskoj strani - vidi napomenu u
            // klasnom komentaru o neizvjesnosti oko ponasanja upitnih parametara.
            return Arrays.stream(entries)
                    .filter(e -> currencyCode.equalsIgnoreCase(e.valuta()))
                    .map(e -> new AbstractMap.SimpleEntry<>(parseDate(e.datum_primjene()), parseRate(e.srednji_tecaj())))
                    .filter(e -> e.getKey() != null && e.getValue() != null && !e.getKey().isAfter(date))
                    .max(Comparator.comparing(AbstractMap.SimpleEntry::getKey))
                    .map(AbstractMap.SimpleEntry::getValue)
                    .orElse(null);

        } catch (Exception e) {
            // Mrezni problem, timeout, HNB privremeno nedostupan i sl. -
            // vracamo null, pozivatelj (parser) radi fallback na sirov iznos.
            return null;
        }
    }

    private LocalDate parseDate(String raw) {
        if (raw == null) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal parseRate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Jedan redak HNB tecajne liste (tecajn-eur v3, JSON). Imena polja odgovaraju JSON kljucevima. */
    private record HnbRateEntry(String datum_primjene, String valuta, String srednji_tecaj) {}
}
