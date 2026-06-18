package crawling.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "tenders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tender {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String externalId;

    private String tenderNumber;

    @Column(length = 1000)
    private String title;

    @Column(length = 500)
    private String company;

    private String procurementType;
    private String procurementMethod;

    @Column(length = 500)
    private String detailUrl;

    @Column(precision = 19, scale = 2)
    private BigDecimal plannedAmount;

    private LocalDateTime publishedAt;
    private LocalDateTime deadline;

    @Column(nullable = false)
    private LocalDateTime discoveredAt;

    private boolean matchedFilter;

    private String matchedCategory;
    private Boolean experienceRequired;

    @Column(length = 200)
    private String city;

    @Column(length = 500)
    private String experienceText;
}
