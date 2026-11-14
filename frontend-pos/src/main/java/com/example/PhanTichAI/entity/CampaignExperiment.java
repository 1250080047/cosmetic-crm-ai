package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Bảng campaign_experiments: một lần đo hiệu quả ưu đãi bằng nhóm đối chứng, bắt đầu khi chiến lược được bật và
 * kết thúc khi chiến lược bị tắt. Ghi bởi ExperimentService.
 */
@Entity
@Table(name = "campaign_experiments")
public class CampaignExperiment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** strategies.id lúc bật (không khóa ngoại: chiến lược được thay mới khi áp dụng phân cụm khác). */
    @Column(name = "strategy_id", nullable = false)
    private Integer strategyId;

    @Column(name = "segment_id")
    private Integer segmentId;

    @Column(name = "strategy_title", nullable = false, length = 150)
    private String strategyTitle;

    /** Phần trăm khách giữ lại làm nhóm đối chứng (không nhận ưu đãi). */
    @Column(name = "control_percent", nullable = false)
    private Integer controlPercent = 10;

    /** Số ngày đo tỉ lệ quay lại. */
    @Column(name = "window_days", nullable = false)
    private Integer windowDays = 30;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** NULL: đang đo. */
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "started_by", length = 100)
    private String startedBy;

    /** Số khách của nhóm lúc bắt đầu (cả hai phía). */
    @Column(name = "cohort_size", nullable = false)
    private Integer cohortSize = 0;

    public CampaignExperiment() {}

    public Integer getId() { return id; }
    public Integer getStrategyId() { return strategyId; }
    public Integer getSegmentId() { return segmentId; }
    public String getStrategyTitle() { return strategyTitle; }
    public Integer getControlPercent() { return controlPercent; }
    public Integer getWindowDays() { return windowDays; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public String getStartedBy() { return startedBy; }
    public Integer getCohortSize() { return cohortSize; }
}
