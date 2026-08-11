package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Report {

    public enum Period { WEEKLY, MONTHLY, YEARLY }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Period period;

    @Column(name = "start_date", nullable = false)
    private OffsetDateTime startDate;

    @Column(name = "end_date", nullable = false)
    private OffsetDateTime endDate;

    @Column(name = "total_income", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalIncome;

    @Column(name = "total_expense", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalExpense;

    @Column(name = "dominant_category", length = 32)
    private String dominantCategory;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "categories_json", columnDefinition = "text")
    private String categoriesJson;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
