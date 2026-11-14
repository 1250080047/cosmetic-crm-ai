package com.example.PhanTichAI.dto;

import java.math.BigDecimal;
import java.util.List;

public class CheckoutRequest {
    private Integer customerId;
    private BigDecimal totalAmount;
    private BigDecimal discountAmount;
    private BigDecimal finalAmount;
    private String paymentMethod;
    private List<ItemDto> items;
    /** Points the customer uses on this invoice (multiples of 100; 100 points = 10.000 đ). */
    private Integer redeemPoints;

    public Integer getRedeemPoints() { return redeemPoints; }
    public void setRedeemPoints(Integer redeemPoints) { this.redeemPoints = redeemPoints; }

    public static class ItemDto {
        private Integer productId;
        private Integer quantity;
        private BigDecimal unitPrice;
        private boolean gift;
        private boolean fromSuggestion;

        public ItemDto() {}

        public boolean isFromSuggestion() { return fromSuggestion; }
        public void setFromSuggestion(boolean fromSuggestion) { this.fromSuggestion = fromSuggestion; }

        public Integer getProductId() { return productId; }
        public void setProductId(Integer productId) { this.productId = productId; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

        public boolean isGift() { return gift; }
        public void setGift(boolean gift) { this.gift = gift; }
    }

    public CheckoutRequest() {}

    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public BigDecimal getFinalAmount() { return finalAmount; }
    public void setFinalAmount(BigDecimal finalAmount) { this.finalAmount = finalAmount; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public List<ItemDto> getItems() { return items; }
    public void setItems(List<ItemDto> items) { this.items = items; }
}
