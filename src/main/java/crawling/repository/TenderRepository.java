package crawling.repository;

import crawling.entity.Tender;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenderRepository extends JpaRepository<Tender, Long> {
    boolean existsByExternalId(String externalId);
    java.util.Optional<Tender> findByExternalId(String externalId);
}
