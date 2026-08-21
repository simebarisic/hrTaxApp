package hr.porezno.taxapp.repository;

import hr.porezno.taxapp.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findAllByOrderByDateTimeAsc();

    /** Vraca one od zadanih vanjskih ID-eva koji vec postoje u bazi - za deduplikaciju pri uploadu. */
    @Query("select t.externalId from Transaction t where t.externalId in :ids")
    Set<String> findExistingExternalIds(@Param("ids") Collection<String> ids);
}
