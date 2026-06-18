package crawling.service;

import crawling.entity.FilterSettings;
import crawling.repository.FilterSettingsRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class FilterSettingsService {

    private static final long SETTINGS_ID = 1L;

    private final FilterSettingsRepository repository;

    @PostConstruct
    void init() {
        if (!repository.existsById(SETTINGS_ID)) {
            FilterSettings defaults = FilterSettings.builder()
                    .id(SETTINGS_ID)
                    .enabledCategoryIds("IT")
                    .minPrice(null)
                    .maxPrice(null)
                    .experienceCheckEnabled(false)
                    .activeOnlyFilter(true)
                    .build();
            repository.save(defaults);
            log.info("Initialized filter settings — IT category enabled by default");
        }
    }

    public FilterSettings get() {
        return repository.findById(SETTINGS_ID)
                .orElseThrow(() -> new IllegalStateException("FilterSettings row missing"));
    }

    @Transactional
    public void save(FilterSettings settings) {
        settings.setId(SETTINGS_ID);
        repository.save(settings);
    }

    public List<String> getEnabledCategoryIds() {
        String raw = get().getEnabledCategoryIds();
        if (raw == null || raw.isBlank()) return List.of();
        return new ArrayList<>(Arrays.asList(raw.split(",")));
    }

    @Transactional
    public boolean toggleCategory(String categoryId) {
        FilterSettings settings = get();
        List<String> enabled = getEnabledCategoryIds();
        boolean nowEnabled;
        if (enabled.contains(categoryId)) {
            enabled.remove(categoryId);
            nowEnabled = false;
        } else {
            enabled.add(categoryId);
            nowEnabled = true;
        }
        settings.setEnabledCategoryIds(String.join(",", enabled));
        save(settings);
        return nowEnabled;
    }

    @Transactional
    public void setBudget(BigDecimal min, BigDecimal max) {
        FilterSettings settings = get();
        settings.setMinPrice(min);
        settings.setMaxPrice(max);
        save(settings);
    }

    @Transactional
    public void setExperienceCheck(boolean enabled) {
        FilterSettings settings = get();
        settings.setExperienceCheckEnabled(enabled);
        save(settings);
    }

    @Transactional
    public void setActiveOnly(boolean enabled) {
        FilterSettings settings = get();
        settings.setActiveOnlyFilter(enabled);
        save(settings);
    }
}
