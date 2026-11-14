package com.example.PhanTichAI.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

/** Bảng order_item_lots: dòng hóa đơn lấy bao nhiêu hàng từ lô nào (truy vết khi cần thu hồi một lô). */
@Entity
@Table(name = "order_item_lots")
@IdClass(OrderItemLot.Key.class)
public class OrderItemLot {

    @Id
    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Id
    @Column(name = "lot_id", nullable = false)
    private Integer lotId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    public OrderItemLot() {}

    public Long getOrderItemId() { return orderItemId; }
    public Integer getLotId() { return lotId; }
    public Integer getQuantity() { return quantity; }

    /** Khóa ghép (order_item_id, lot_id). */
    public static class Key implements Serializable {
        private Long orderItemId;
        private Integer lotId;

        public Key() {}

        @Override public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(orderItemId, key.orderItemId) && Objects.equals(lotId, key.lotId);
        }
        @Override public int hashCode() { return Objects.hash(orderItemId, lotId); }
    }
}
