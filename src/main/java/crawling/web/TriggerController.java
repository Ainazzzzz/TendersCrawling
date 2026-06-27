package crawling.web;

import crawling.dto.TenderDto;
import crawling.repository.TenderRepository;
import crawling.scheduler.TenderCheckScheduler;
import crawling.service.TenderScraperService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class TriggerController {

    private final TenderScraperService scraper;
    private final TenderCheckScheduler scheduler;
    private final TenderRepository repository;

    // Посмотреть что парсится (без сохранения в БД и без отправки в Telegram)
    @GetMapping("/preview")
    public List<TenderDto> preview(@RequestParam(defaultValue = "1") int pages) {
        return scraper.fetchLatest();
    }

    // Полный прогон: сохранить новые + отправить уведомления
    @GetMapping("/trigger")
    public Map<String, String> trigger() {
        scheduler.checkTenders();
        return Map.of("status", "done — check logs");
    }

    // Сбросить matched_filter для всех тендеров (чтобы переотправить уведомления)
    @GetMapping("/reset-matched")
    public Map<String, Object> resetMatched() {
        List<?> all = repository.findAll();
        long count = all.stream()
                .filter(t -> t instanceof crawling.entity.Tender tender && tender.isMatchedFilter())
                .peek(t -> { crawling.entity.Tender tender = (crawling.entity.Tender) t; tender.setMatchedFilter(false); })
                .count();
        repository.saveAll((List<crawling.entity.Tender>) all);
        return Map.of("reset", count);
    }
}
