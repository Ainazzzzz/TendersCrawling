package crawling.service;

import com.fasterxml.jackson.databind.JsonNode;
import crawling.config.FilterProperties;
import crawling.config.TelegramProperties;
import crawling.entity.FilterSettings;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramBotService {

    private final TelegramApiClient api;
    private final TelegramProperties telegram;
    private final FilterProperties filterProperties;
    private final FilterSettingsService settingsService;
    private final crawling.scheduler.TenderCheckScheduler scheduler;

    private volatile long updateOffset = 0;

    @PostConstruct
    void startPolling() {
        if (telegram.getBotToken() == null || telegram.getBotToken().isBlank()) {
            log.warn("TELEGRAM_BOT_TOKEN not set — command polling disabled");
            return;
        }
        Thread t = new Thread(this::pollLoop, "tg-poll");
        t.setDaemon(true);
        t.start();
        log.info("Telegram bot polling started");
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private void pollLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                pollOnce();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Telegram poll error: {}", e.getMessage());
                try { Thread.sleep(5_000); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private void pollOnce() throws Exception {
        JsonNode root = api.getUpdates(updateOffset, 30);
        if (!root.path("ok").asBoolean(false)) return;
        for (JsonNode update : root.path("result")) {
            try { processUpdate(update); } catch (Exception e) {
                log.error("Error handling update: {}", e.getMessage());
            }
            updateOffset = update.path("update_id").asLong() + 1;
        }
    }

    private void processUpdate(JsonNode update) {
        if (update.has("message")) {
            String chatId = update.path("message").path("chat").path("id").asText();
            // Any message → open main menu
            showMenu(chatId, null);

        } else if (update.has("callback_query")) {
            JsonNode cb   = update.path("callback_query");
            String cbId   = cb.path("id").asText();
            String chatId = cb.path("message").path("chat").path("id").asText();
            long   msgId  = cb.path("message").path("message_id").asLong();
            String data   = cb.path("data").asText();
            handleCallback(chatId, msgId, cbId, data);
        }
    }

    // ── Callback dispatcher ───────────────────────────────────────────────────

    private void handleCallback(String chatId, long msgId, String cbId, String data) {
        switch (data) {
            case "menu"           -> { api.answerCallbackQuery(cbId, null); showMenu(chatId, msgId); }
            case "cat"            -> { api.answerCallbackQuery(cbId, null); showCategories(chatId, msgId); }
            case "budget"         -> { api.answerCallbackQuery(cbId, null); showBudget(chatId, msgId); }
            case "settings"       -> { api.answerCallbackQuery(cbId, null); showSettings(chatId, msgId); }
            case "check"          -> handleCheck(chatId, msgId, cbId);
            case "toggle_exp"     -> { settingsService.setExperienceCheck(!settingsService.get().isExperienceCheckEnabled()); api.answerCallbackQuery(cbId, null); showSettings(chatId, msgId); }
            case "toggle_active"  -> { settingsService.setActiveOnly(!settingsService.get().isActiveOnlyFilter()); api.answerCallbackQuery(cbId, null); showSettings(chatId, msgId); }
            default -> {
                if (data.startsWith("toggle_cat_")) {
                    String catId = data.substring("toggle_cat_".length());
                    settingsService.toggleCategory(catId);
                    api.answerCallbackQuery(cbId, null);
                    showCategories(chatId, msgId);
                } else if (data.startsWith("budget_")) {
                    applyBudget(data.substring("budget_".length()));
                    api.answerCallbackQuery(cbId, null);
                    showBudget(chatId, msgId);
                }
            }
        }
    }

    // ── Screens ───────────────────────────────────────────────────────────────

    /** Main menu — sent as a new message or edits existing one. */
    void showMenu(String chatId, Long msgId) {
        FilterSettings s = settingsService.get();
        List<String> enabledIds = settingsService.getEnabledCategoryIds();

        StringBuilder sb = new StringBuilder();
        sb.append("🤖 <b>TenderBot — мониторинг закупок</b>\n\n");

        if (enabledIds.isEmpty()) {
            sb.append("⚠️ Нет активных категорий. Нажмите <b>Категории</b> чтобы выбрать.\n");
        } else {
            sb.append("<b>Категории:</b> ");
            for (String id : enabledIds) {
                FilterProperties.CategoryConfig cat = filterProperties.findById(id);
                if (cat != null) sb.append(cat.getEmoji()).append(" ").append(cat.getName()).append("  ");
            }
            sb.append("\n");
        }

        sb.append("<b>Макс. бюджет:</b> ");
        if (s.getMaxPrice() == null || s.getMaxPrice().compareTo(BigDecimal.ZERO) == 0) {
            sb.append("не задан\n");
        } else {
            sb.append(String.format("%,.0f KGS\n", s.getMaxPrice()));
        }

        sb.append("<b>Только актуальные:</b> ").append(s.isActiveOnlyFilter() ? "✅" : "⬜").append("\n");
        sb.append("<b>Проверка опыта:</b> ").append(s.isExperienceCheckEnabled() ? "✅" : "⬜").append("\n");

        List<List<Map<String, String>>> kb = List.of(
                List.of(btn("🔍 Проверить сейчас", "check")),
                List.of(btn("📂 Категории", "cat"), btn("💰 Бюджет", "budget")),
                List.of(btn("⚙️ Настройки", "settings"))
        );

        if (msgId != null) {
            api.editMessageText(chatId, msgId, sb.toString(), kb);
        } else {
            api.sendMessage(chatId, sb.toString(), kb);
        }
    }

    private void showCategories(String chatId, long msgId) {
        List<String> enabledIds = settingsService.getEnabledCategoryIds();
        List<List<Map<String, String>>> kb = new ArrayList<>();

        for (FilterProperties.CategoryConfig cat : filterProperties.getCategories()) {
            boolean on = enabledIds.contains(cat.getId());
            String label = (on ? "✅ " : "⬜ ") + cat.getEmoji() + " " + cat.getName();
            kb.add(List.of(btn(label, "toggle_cat_" + cat.getId())));
        }
        kb.add(List.of(btn("◀️ Назад", "menu")));

        String text = "<b>📂 Категории</b>\n\nНажмите чтобы включить или выключить.\nУведомления приходят только по включённым категориям.";
        api.editMessageText(chatId, msgId, text, kb);
    }

    private void showBudget(String chatId, long msgId) {
        BigDecimal cur = settingsService.get().getMaxPrice();
        List<List<Map<String, String>>> kb = new ArrayList<>();

        record Option(String label, String value, BigDecimal amount) {}
        List<Option> options = List.of(
                new Option("Без ограничения",      "0",        BigDecimal.ZERO),
                new Option("до 100,000 KGS",       "100000",   new BigDecimal("100000")),
                new Option("до 200,000 KGS",       "200000",   new BigDecimal("200000")),
                new Option("до 500,000 KGS",       "500000",   new BigDecimal("500000")),
                new Option("до 1,000,000 KGS",     "1000000",  new BigDecimal("1000000")),
                new Option("до 5,000,000 KGS",     "5000000",  new BigDecimal("5000000"))
        );

        for (Option o : options) {
            boolean active = (cur == null || cur.compareTo(BigDecimal.ZERO) == 0)
                    ? o.amount().compareTo(BigDecimal.ZERO) == 0
                    : cur.compareTo(o.amount()) == 0;
            String label = (active ? "✅ " : "") + o.label();
            kb.add(List.of(btn(label, "budget_" + o.value())));
        }
        kb.add(List.of(btn("◀️ Назад", "menu")));

        String text = "<b>💰 Максимальная сумма тендера</b>\n\nТендеры дороже выбранной суммы будут игнорироваться.";
        api.editMessageText(chatId, msgId, text, kb);
    }

    private void showSettings(String chatId, long msgId) {
        FilterSettings s = settingsService.get();
        List<List<Map<String, String>>> kb = List.of(
                List.of(btn(
                        (s.isActiveOnlyFilter() ? "✅" : "⬜") + " Только актуальные тендеры",
                        "toggle_active")),
                List.of(btn(
                        (s.isExperienceCheckEnabled() ? "✅" : "⬜") + " Без требования опыта",
                        "toggle_exp")),
                List.of(btn("◀️ Назад", "menu"))
        );

        StringBuilder sb = new StringBuilder();
        sb.append("<b>⚙️ Настройки</b>\n\n");
        sb.append("<b>Только актуальные</b> — скрывает тендеры с истёкшим сроком подачи.\n\n");
        sb.append("<b>Без требования опыта</b> — бот открывает страницу каждого тендера и проверяет наличие требования опыта. Немного замедляет работу.");

        api.editMessageText(chatId, msgId, sb.toString(), kb);
    }

    private void handleCheck(String chatId, long msgId, String cbId) {
        api.answerCallbackQuery(cbId, "Запускаю проверку...");
        api.editMessageText(chatId, msgId,
                "⏳ <b>Проверяю тендеры...</b>\n\nУведомления придут если найдутся совпадения.", null);

        new Thread(() -> {
            try {
                scheduler.checkTenders();
            } catch (Exception e) {
                log.error("Manual check failed: {}", e.getMessage());
                api.sendMessage(chatId, "❌ Ошибка при проверке: " + e.getMessage(), null);
            } finally {
                // Mark the ⏳ message as done, then send a FRESH menu at the bottom
                // so the user doesn't have to scroll up to find the buttons
                api.editMessageText(chatId, msgId, "✅ Проверка завершена", null);
                showMenu(chatId, null);
            }
        }, "manual-check").start();
    }

    /** Called by the scheduler after automated checks to keep the menu visible at the bottom. */
    public void sendMenuToDefaultChat() {
        String chatId = telegram.getChatId();
        if (chatId != null && !chatId.isBlank()) {
            showMenu(chatId, null);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyBudget(String valueStr) {
        try {
            BigDecimal val = new BigDecimal(valueStr);
            BigDecimal max = val.compareTo(BigDecimal.ZERO) == 0 ? null : val;
            settingsService.setBudget(settingsService.get().getMinPrice(), max);
        } catch (NumberFormatException ignored) {}
    }

    private static Map<String, String> btn(String text, String data) {
        return Map.of("text", text, "callback_data", data);
    }
}
