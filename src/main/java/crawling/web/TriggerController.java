package crawling.web;

import crawling.dto.TenderDto;
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
}
