package com.example.PhanTichAI.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/** Bảng campaign_assignments: khách thuộc phía nhận ưu đãi (OFFER) hay nhóm đối chứng (CONTROL) của một lần đo. */
@Entity
@Table(name = "campaign_assignments")
@IdClass(CampaignAssignment.Key.class)
public class CampaignAssignment {

    @Id
    @Column(name = "experiment_id", nullable = false)
    private Integer experimentId;

    @Id
    @Column(name = "customer_id", nullable = false)
    private Integer customerId;

    @Column(name = "arm", nullable = false, length = 10)
    private String arm;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    public CampaignAssignment() {}

    public Integer getExperimentId() { return experimentId; }
    public Integer getCustomerId() { return customerId; }
    public String getArm() { return arm; }
    public LocalDateTime getAssignedAt() { return assignedAt; }

    /** Khóa ghép (experiment_id, customer_id). */
    public static class Key implements Serializable {
        private Integer experimentId;
        private Integer customerId;

        public Key() {}

        @Override public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(experimentId, key.experimentId) && Objects.equals(customerId, key.customerId);
        }
        @Override public int hashCode() { return Objects.hash(experimentId, customerId); }
    }
}
