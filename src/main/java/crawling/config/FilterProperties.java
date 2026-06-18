package crawling.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "tenderbot")
public class FilterProperties {

    private List<CategoryConfig> categories = new ArrayList<>();

    @Data
    public static class CategoryConfig {
        private String id;
        private String name;
        private String emoji;
        private List<String> keywords = new ArrayList<>();
    }

    public CategoryConfig findById(String id) {
        return categories.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElse(null);
    }
}
