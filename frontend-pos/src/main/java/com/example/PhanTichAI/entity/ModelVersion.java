package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Bảng model_versions: các phiên bản mô hình phân cụm LRFM (v1, v2, ...).
 * model_json lưu "thước đo" của phiên bản: ngưỡng cắt, mean/std, tâm nhóm, tên nhóm và chỉ số đánh giá.
 * Chỉ một phiên bản ACTIVE; phiên bản cũ chuyển ARCHIVED và không bị xóa.
 * Dịch vụ AI (Python) ghi bảng này khi huấn luyện, nên phía Java chỉ đọc (@Immutable).
 */
@Entity
@Immutable
@Table(name = "model_versions")
public class ModelVersion {

    public static final String ACTIVE = "ACTIVE";
    public static final String ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** Mã phiên bản duy nhất (chuỗi hex), Spring dùng để biết ưu đãi thuộc mô hình nào. */
    @Column(name = "version", nullable = false, unique = true, length = 64)
    private String version;

    /** Tên hiển thị: v1, v2, ... */
    @Column(name = "label", nullable = false, length = 20)
    private String label;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    /** Số nhóm khách của phiên bản. */
    @Column(name = "k", nullable = false)
    private Integer k;

    /** Ngày "đo" khách khi mô hình học. */
    @Column(name = "reference_date", nullable = false)
    private LocalDate referenceDate;

    @Column(name = "training_source", nullable = false, length = 300)
    private String trainingSource;

    @Column(name = "training_rows", nullable = false)
    private Integer trainingRows;

    @Column(name = "model_json", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    private String modelJson;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    public ModelVersion() {}

    public Integer getId() { return id; }
    public String getVersion() { return version; }
    public String getLabel() { return label; }
    public String getStatus() { return status; }
    public boolean isActive() { return ACTIVE.equals(status); }
    public Integer getK() { return k; }
    public LocalDate getReferenceDate() { return referenceDate; }
    public String getTrainingSource() { return trainingSource; }
    public Integer getTrainingRows() { return trainingRows; }
    public String getModelJson() { return modelJson; }
    public String getReason() { return reason; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getActivatedAt() { return activatedAt; }
    public LocalDateTime getArchivedAt() { return archivedAt; }
}
