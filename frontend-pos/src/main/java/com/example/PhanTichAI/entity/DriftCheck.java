package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Bảng drift_checks: nhật ký kiểm tra lệch dữ liệu (bước 7) — khách hiện tại còn giống dữ liệu nền không.
 * Dịch vụ AI (Python) ghi bảng này, nên phía Java chỉ đọc (@Immutable).
 */
@Entity
@Immutable
@Table(name = "drift_checks")
public class DriftCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** model_versions.version của mô hình được kiểm tra. */
    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    /** Ngày đo khách khi kiểm tra. */
    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    /** Số khách mua trong 12 tháng tính tới as_of_date. */
    @Column(name = "sample_size", nullable = false)
    private Integer sampleSize;

    @Column(name = "drift_detected", nullable = false)
    private Boolean driftDetected;

    /** Toàn bộ kết quả (lý do, từng chỉ số L/R/F/M, tỷ trọng nhóm) dạng JSON. */
    @Column(name = "result_json", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    private String resultJson;

    public DriftCheck() {}

    public Integer getId() { return id; }
    public String getModelVersion() { return modelVersion; }
    public LocalDateTime getCheckedAt() { return checkedAt; }
    public LocalDate getAsOfDate() { return asOfDate; }
    public Integer getSampleSize() { return sampleSize; }
    public Boolean getDriftDetected() { return driftDetected; }
    public String getResultJson() { return resultJson; }
}
