package crawling.service;

import crawling.config.FilterProperties;
import crawling.dto.TenderDto;
import crawling.entity.FilterSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenderFilterService {

    private final FilterProperties filterProperties;
    private final FilterSettingsService settingsService;

    /**
     * Returns true if the tender passes deadline, category, and price filters.
     * Does NOT check experience (that requires an HTTP request to the detail page).
     */
    public boolean matchesBasic(TenderDto tender) {
        if (!matchesDeadline(tender)) return false;
        if (!matchesCategories(tender)) return false;
        if (!matchesPrice(tender)) return false;
        return true;
    }

    /**
     * Returns the ID of the first enabled category whose keywords match the tender,
     * or null if none match.
     */
    public String findMatchedCategoryId(TenderDto tender) {
        List<String> enabledIds = settingsService.getEnabledCategoryIds();
        if (enabledIds.isEmpty()) return null;

        String haystack = buildSearchText(tender).toLowerCase();

        return filterProperties.getCategories().stream()
                .filter(cat -> enabledIds.contains(cat.getId()))
                .filter(cat -> cat.getKeywords().stream()
                        .anyMatch(kw -> haystack.contains(kw.toLowerCase())))
                .map(FilterProperties.CategoryConfig::getId)
                .findFirst()
                .orElse(null);
    }

    private boolean matchesDeadline(TenderDto tender) {
        FilterSettings settings = settingsService.get();
        if (!settings.isActiveOnlyFilter()) return true;
        if (tender.getDeadline() == null) return true;
        return tender.getDeadline().isAfter(LocalDateTime.now());
    }

    private boolean matchesCategories(TenderDto tender) {
        return findMatchedCategoryId(tender) != null;
    }

    private boolean matchesPrice(TenderDto tender) {
        BigDecimal amount = tender.getPlannedAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) return true;

        FilterSettings settings = settingsService.get();
        BigDecimal min = settings.getMinPrice();
        BigDecimal max = settings.getMaxPrice();

        if (min != null && min.compareTo(BigDecimal.ZERO) > 0 && amount.compareTo(min) < 0) return false;
        if (max != null && max.compareTo(BigDecimal.ZERO) > 0 && amount.compareTo(max) > 0) return false;
        return true;
    }

    private String buildSearchText(TenderDto t) {
        StringBuilder sb = new StringBuilder();
        if (t.getTitle() != null)           sb.append(t.getTitle()).append(' ');
        if (t.getProcurementType() != null) sb.append(t.getProcurementType()).append(' ');
        if (t.getCompany() != null)         sb.append(t.getCompany()).append(' ');
        return sb.toString();
    }
}
