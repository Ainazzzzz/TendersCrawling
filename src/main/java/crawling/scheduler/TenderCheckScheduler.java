package crawling.scheduler;

import crawling.dto.DetailInfo;
import crawling.dto.TenderDto;
import crawling.entity.FilterSettings;
import crawling.entity.Tender;
import crawling.repository.TenderRepository;
import crawling.service.FilterSettingsService;
import crawling.service.TelegramBotService;
import crawling.service.TelegramNotificationService;
import crawling.service.TenderFilterService;
import crawling.service.TenderScraperService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class TenderCheckScheduler {

    private final TenderScraperService scraper;
    private final TenderFilterService filterService;
    private final FilterSettingsService settingsService;
    private final TenderRepository repository;
    private final TelegramNotificationService notifier;

    @Autowired @Lazy
    private TelegramBotService botService;

    @Scheduled(cron = "${tenderbot.schedule.cron}")
    public void checkTenders() {
        checkTenders(true);
    }

    /** @param sendMenuAfter true for scheduled runs; false when called manually (caller handles its own menu) */
    public void checkTenders(boolean sendMenuAfter) {
        log.info("=== Tender check started ===");

        List<TenderDto> fetched = scraper.fetchLatest();
        log.info("Fetched {} tenders from portal", fetched.size());

        FilterSettings settings = settingsService.get();
        int newCount = 0;
        int matchCount = 0;

        for (TenderDto dto : fetched) {
            if (repository.existsByExternalId(dto.getExternalId())) {
                // Re-evaluate previously saved but unnotified tenders.
                // This handles the case where filter settings changed after the tender was scraped.
                if (reEvaluateExisting(dto, settings)) matchCount++;
                continue;
            }

            // ── New tender ────────────────────────────────────────────────────
            newCount++;
            boolean basicMatch = filterService.matchesBasic(dto);

            if (basicMatch) {
                dto.setMatchedCategory(filterService.findMatchedCategoryId(dto));

                try { Thread.sleep(500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
                DetailInfo detail = scraper.fetchDetailInfo(dto.getDetailUrl());
                dto.setCity(detail.city());
                dto.setExperienceText(detail.experienceText());
                dto.setExperienceRequired(detail.hasExperience());
                dto.setGuaranteeRequired(detail.guaranteeRequired());

                if (settings.isExperienceCheckEnabled() && detail.hasExperience()) {
                    basicMatch = false;
                }
                if (basicMatch && settings.isGuaranteeCheckEnabled() && detail.guaranteeRequired()) {
                    basicMatch = false;
                }
            }

            repository.save(Tender.builder()
                    .externalId(dto.getExternalId())
                    .tenderNumber(dto.getTenderNumber())
                    .title(dto.getTitle())
                    .company(dto.getCompany())
                    .procurementType(dto.getProcurementType())
                    .procurementMethod(dto.getProcurementMethod())
                    .detailUrl(dto.getDetailUrl())
                    .plannedAmount(dto.getPlannedAmount())
                    .publishedAt(dto.getPublishedAt())
                    .deadline(dto.getDeadline())
                    .discoveredAt(LocalDateTime.now())
                    .matchedFilter(basicMatch)
                    .matchedCategory(dto.getMatchedCategory())
                    .experienceRequired(dto.getExperienceRequired())
                    .guaranteeRequired(dto.getGuaranteeRequired())
                    .city(dto.getCity())
                    .experienceText(dto.getExperienceText())
                    .build());

            if (basicMatch) {
                matchCount++;
                notifier.sendNotification(dto);
            }
        }

        log.info("=== Check complete: {} new, {} matched filter ===", newCount, matchCount);

        // Send fresh menu only when notifications were sent, so the user sees the buttons below them
        if (sendMenuAfter && matchCount > 0) {
            try { botService.sendMenuToDefaultChat(); } catch (Exception e) {
                log.warn("Could not send menu after check: {}", e.getMessage());
            }
        }
    }

    /**
     * Re-checks an already-saved tender against the current filter settings.
     * Returns true if the tender now matches and a notification was sent.
     */
    private boolean reEvaluateExisting(TenderDto dto, FilterSettings settings) {
        Tender existing = repository.findByExternalId(dto.getExternalId()).orElse(null);
        if (existing == null || existing.isMatchedFilter()) return false;

        if (!filterService.matchesBasic(dto)) return false;

        dto.setMatchedCategory(filterService.findMatchedCategoryId(dto));

        // Fetch detail page if any check is enabled and data was never stored
        boolean needDetail = (settings.isExperienceCheckEnabled() && existing.getExperienceRequired() == null)
                || (settings.isGuaranteeCheckEnabled() && existing.getGuaranteeRequired() == null);
        if (needDetail) {
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return false;
            }
            DetailInfo detail = scraper.fetchDetailInfo(dto.getDetailUrl());
            existing.setCity(detail.city());
            existing.setExperienceText(detail.experienceText());
            existing.setExperienceRequired(detail.hasExperience());
            existing.setGuaranteeRequired(detail.guaranteeRequired());
        }

        dto.setCity(existing.getCity());
        dto.setExperienceText(existing.getExperienceText());
        dto.setExperienceRequired(existing.getExperienceRequired());
        dto.setGuaranteeRequired(existing.getGuaranteeRequired());

        if (settings.isExperienceCheckEnabled() && Boolean.TRUE.equals(existing.getExperienceRequired())) {
            repository.save(existing);
            return false;
        }
        if (settings.isGuaranteeCheckEnabled() && Boolean.TRUE.equals(existing.getGuaranteeRequired())) {
            repository.save(existing);
            return false;
        }

        existing.setMatchedFilter(true);
        existing.setMatchedCategory(dto.getMatchedCategory());
        repository.save(existing);
        notifier.sendNotification(dto);
        log.info("Re-matched existing tender {}", dto.getExternalId());
        return true;
    }
}
