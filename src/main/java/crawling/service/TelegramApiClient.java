package crawling.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import crawling.config.TelegramProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramApiClient {

    private static final String BASE = "https://api.telegram.org/bot";

    private final TelegramProperties telegram;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public boolean isConfigured() {
        return telegram.getBotToken() != null && !telegram.getBotToken().isBlank()
                && telegram.getChatId() != null && !telegram.getChatId().isBlank();
    }

    /** Send plain text or HTML message to the configured chat. */
    public void sendMessage(String text) {
        sendMessage(telegram.getChatId(), text, null);
    }

    /** Send message with optional inline keyboard. */
    public void sendMessage(String chatId, String text, List<List<Map<String, String>>> inlineKeyboard) {
        if (!isConfigured()) {
            log.warn("Telegram not configured — skipping sendMessage");
            return;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "HTML");
            body.put("disable_web_page_preview", "true");
            if (inlineKeyboard != null) {
                body.put("reply_markup", Map.of("inline_keyboard", inlineKeyboard));
            }
            String url = BASE + telegram.getBotToken() + "/sendMessage";
            restTemplate.postForObject(url, body, String.class);
        } catch (Exception e) {
            log.error("sendMessage failed: {}", e.getMessage());
        }
    }

    /** Edit an existing message text and keyboard. */
    public void editMessageText(String chatId, long messageId, String text,
                                List<List<Map<String, String>>> inlineKeyboard) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("message_id", messageId);
            body.put("text", text);
            body.put("parse_mode", "HTML");
            body.put("disable_web_page_preview", "true");
            if (inlineKeyboard != null) {
                body.put("reply_markup", Map.of("inline_keyboard", inlineKeyboard));
            }
            String url = BASE + telegram.getBotToken() + "/editMessageText";
            restTemplate.postForObject(url, body, String.class);
        } catch (Exception e) {
            log.error("editMessageText failed: {}", e.getMessage());
        }
    }

    /** Must be called after receiving every callback_query, otherwise Telegram shows loading spinner. */
    public void answerCallbackQuery(String callbackQueryId, String text) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("callback_query_id", callbackQueryId);
            if (text != null) body.put("text", text);
            String url = BASE + telegram.getBotToken() + "/answerCallbackQuery";
            restTemplate.postForObject(url, body, String.class);
        } catch (Exception e) {
            log.error("answerCallbackQuery failed: {}", e.getMessage());
        }
    }

    /** Long-poll for updates. Blocks up to timeoutSeconds. */
    public JsonNode getUpdates(long offset, int timeoutSeconds) throws Exception {
        String url = BASE + telegram.getBotToken()
                + "/getUpdates?offset=" + offset + "&timeout=" + timeoutSeconds;
        String response = restTemplate.getForObject(url, String.class);
        return objectMapper.readTree(response);
    }
}
