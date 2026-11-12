package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

/**
 * Bảng customer_segments: tên và màu của từng nhóm khách trong mô hình đang áp dụng (id = 0..K-1).
 * Dịch vụ AI (Python) ghi bảng này khi áp dụng mô hình mới, nên phía Java chỉ đọc (@Immutable).
 */
@Entity
@Immutable
@Table(name = "customer_segments")
public class CustomerSegment {

    /** Số thứ tự nhóm do mô hình đặt (không tự tăng). */
    @Id
    private Integer id;

    @Column(name = "segment_name", nullable = false, length = 50)
    private String segmentName;

    @Column(name = "color_code", length = 20)
    private String colorCode;

    public CustomerSegment() {}

    public Integer getId() { return id; }
    public String getSegmentName() { return segmentName; }
    public String getColorCode() { return colorCode; }
}
