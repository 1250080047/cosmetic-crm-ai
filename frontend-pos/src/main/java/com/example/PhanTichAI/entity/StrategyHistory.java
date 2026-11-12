package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.LocalDateTime;

/**
 * Bảng strategy_history: ưu đãi cũ được lưu lại khi áp dụng mô hình mới làm nhóm của nó thay đổi.
 * Dịch vụ AI (Python) ghi bảng này lúc áp dụng mô hình, nên phía Java chỉ đọc (@Immutable).
 */
@Entity
@Immutable
@Table(name = "strategy_history")
public class StrategyHistory {

    /** strategies.id của ưu đãi trước khi bị lưu trữ. */
    @Id
    @Column(name = "original_strategy_id")
    private Integer originalStrategyId;

    @Column(name = "segment_id")
    private Integer segmentId;

    /** Toàn bộ dòng ưu đãi lúc lưu trữ (tên, mức giảm, quà, lời tư vấn) dạng JSON. */
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    private String snapshotJson;

    @Column(name = "archived_at", nullable = false)
    private LocalDateTime archivedAt;

    /** model_versions.version của mô hình mới làm ưu đãi này bị lưu trữ. */
    @Column(name = "deployment_version", nullable = false, length = 64)
    private String deploymentVersion;

    public StrategyHistory() {}

    public Integer getOriginalStrategyId() { return originalStrategyId; }
    public Integer getSegmentId() { return segmentId; }
    public String getSnapshotJson() { return snapshotJson; }
    public LocalDateTime getArchivedAt() { return archivedAt; }
    public String getDeploymentVersion() { return deploymentVersion; }
}
