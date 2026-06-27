package crawling.service;

import crawling.config.FilterProperties;
import crawling.dto.TenderDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramNotificationService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final Map<String, String> TYPE_RU = Map.of(
            "goods",    "Товары",
            "services", "Услуги",
            "works",    "Работы"
    );

    private static final Map<String, String> METHOD_RU = Map.ofEntries(
            Map.entry("one stage",               "Одноэтапный конкурс"),
            Map.entry("two stage",               "Двухэтапный конкурс"),
            Map.entry("request for quotations",  "Котировка цен"),
            Map.entry("request for proposals",   "Запрос предложений"),
            Map.entry("direct procurement",      "Прямая закупка"),
            Map.entry("direct",                  "Прямая закупка")
    );

    private final TelegramApiClient api;
    private final FilterProperties filterProperties;

    public void sendNotification(TenderDto tender) {
        if (!api.isConfigured()) {
            log.warn("Telegram not configured — skipping notification for {}", tender.getExternalId());
            return;
        }
        api.sendMessage(formatMessage(tender, "🆕 <b>Новый тендер</b>"));
        log.info("Sent notification for tender {}", tender.getExternalId());
    }

    public void sendSearchResult(String chatId, TenderDto tender) {
        if (!api.isConfigured()) return;
        api.sendMessage(chatId, formatMessage(tender, "🔍 <b>Без гарантийного взноса</b>"), null);
    }

    public void sendMessage(String text) {
        api.sendMessage(text);
    }

    private String formatMessage(TenderDto t, String header) {
        StringBuilder sb = new StringBuilder();
        sb.append(header);

        // Category badge
        if (t.getMatchedCategory() != null) {
            FilterProperties.CategoryConfig cat = filterProperties.findById(t.getMatchedCategory());
            if (cat != null) sb.append("  ").append(cat.getEmoji()).append(" ").append(cat.getName());
        }
        sb.append("\n\n");

        sb.append("<b>").append(escape(t.getTitle())).append("</b>\n\n");

        // City + company on one line
        if (t.getCity() != null) {
            sb.append("📍 <b>").append(escape(t.getCity())).append("</b>");
            if (t.getCompany() != null) sb.append(" — ").append(escape(t.getCompany()));
        } else if (t.getCompany() != null) {
            sb.append("Заказчик: ").append(escape(t.getCompany()));
        }
        sb.append('\n');

        // Type + method
        String type   = translateOrKeep(TYPE_RU,   t.getProcurementType());
        String method = translateOrKeep(METHOD_RU, t.getProcurementMethod());
        sb.append("📄 ").append(escape(type));
        if (!method.equals(type)) sb.append(" · ").append(escape(method));
        sb.append('\n');

        // Amount
        if (t.getPlannedAmount() != null && t.getPlannedAmount().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("💰 <b>").append(String.format("%,.0f", t.getPlannedAmount())).append(" KGS</b>\n");
        }

        // Deadline with days remaining
        if (t.getDeadline() != null) {
            long daysLeft = java.time.Duration.between(LocalDateTime.now(), t.getDeadline()).toDays();
            sb.append("⏰ Срок: ").append(t.getDeadline().format(FMT));
            if (daysLeft >= 0) sb.append(" (").append(daysLeft).append(" дн.)");
            sb.append('\n');
        }

        // Experience requirement
        if (t.getExperienceText() != null && !t.getExperienceText().isBlank()) {
            sb.append("⚠️ Опыт: ").append(escape(t.getExperienceText())).append('\n');
        } else if (Boolean.FALSE.equals(t.getExperienceRequired())) {
            sb.append("✅ Опыт не требуется\n");
        }

        sb.append("\n<a href=\"").append(t.getDetailUrl()).append("\">📎 Открыть объявление</a>");
        return sb.toString();
    }

    private String translateOrKeep(Map<String, String> map, String value) {
        if (value == null || value.isBlank()) return "";
        return map.getOrDefault(value.toLowerCase().trim(), value);
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
