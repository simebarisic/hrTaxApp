package hr.porezno.taxapp.repository;

import hr.porezno.taxapp.model.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {
    Optional<ExchangeRate> findByCurrencyAndRateDate(String currency, LocalDate rateDate);
}
