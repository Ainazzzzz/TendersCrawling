package crawling.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "filter_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FilterSettings {

    @Id
    private Long id;

    // Comma-separated enabled category IDs, e.g. "IT,FOOD,SERVICES"
    @Column(length = 500)
    private String enabledCategoryIds;

    @Column(precision = 19, scale = 2)
    private BigDecimal minPrice;

    @Column(precision = 19, scale = 2)
    private BigDecimal maxPrice;

    // When true: fetch detail page and skip tenders requiring prior experience
    @Column(nullable = false)
    @Builder.Default
    private boolean experienceCheckEnabled = false;

    // When true: skip tenders whose submission deadline has already passed
    @Column(nullable = false)
    @Builder.Default
    private boolean activeOnlyFilter = true;
}
