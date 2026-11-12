package com.example.PhanTichAI.service;

import com.example.PhanTichAI.entity.LoyaltyPoint;
import com.example.PhanTichAI.repository.LoyaltyPointRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/** Loyalty points: 1 point for every 10.000 đ the customer actually pays (after the offer), rounded down. */
@Service
public class LoyaltyService {
    public static final BigDecimal VND_PER_POINT = new BigDecimal("10000");
    private final LoyaltyPointRepository points;

    public LoyaltyService(LoyaltyPointRepository points) { this.points = points; }

    public static int pointsFor(BigDecimal paid) {
        if (paid == null || paid.signum() <= 0) return 0;
        return paid.divide(VND_PER_POINT, 0, RoundingMode.DOWN).intValueExact();
    }

    /** Adds the points of one invoice (in the checkout transaction) and returns how many were added. */
    public int award(int customerId, String transactionNo, BigDecimal paid, LocalDateTime at) {
        int earned = pointsFor(paid);
        if (earned > 0) points.save(new LoyaltyPoint(customerId, transactionNo, earned, "Tích điểm hóa đơn " + transactionNo, at));
        return earned;
    }

    public int balance(int customerId) {
        Long total = points.balanceOf(customerId);
        return total == null ? 0 : Math.toIntExact(total);
    }

    public List<LoyaltyPoint> recent(int customerId) { return points.findTop10ByCustomerIdOrderByCreatedAtDescIdDesc(customerId); }
}
