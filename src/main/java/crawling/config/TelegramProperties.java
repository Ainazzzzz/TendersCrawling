package crawling.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tenderbot.telegram")
public class TelegramProperties {
    private String botToken;
    private String chatId;
}
