package crawling.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenderDto {
    private String externalId;
    private String tenderNumber;
    private String title;
    private String company;
    private String procurementType;
    private String procurementMethod;
    private String detailUrl;
    private BigDecimal plannedAmount;
    private LocalDateTime publishedAt;
    private LocalDateTime deadline;

    // Set during filter evaluation and detail-page fetch
    private String matchedCategory;
    private Boolean experienceRequired;
    private Boolean guaranteeRequired;
    private String city;           // delivery address city extracted from detail page
    private String experienceText; // actual requirement text, null if none
}
