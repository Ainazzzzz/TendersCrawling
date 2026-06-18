package crawling.repository;

import crawling.entity.FilterSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FilterSettingsRepository extends JpaRepository<FilterSettings, Long> {
}
