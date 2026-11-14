package com.example.PhanTichAI.service;

import com.example.PhanTichAI.dto.CheckoutRequest;
import com.example.PhanTichAI.entity.*;
import com.example.PhanTichAI.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PosCheckoutService {
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final CustomerOfferService offers;
    private final ShiftService shifts;
    private final LoyaltyService loyalty;
    private final InventoryService inventory;

    public PosCheckoutService(ProductRepository products, CustomerRepository customers, OrderRepository orders,
                              OrderItemRepository orderItems, CustomerOfferService offers) {
        this(products, customers, orders, orderItems, offers, null, null, null);
    }
    public PosCheckoutService(ProductRepository products, CustomerRepository customers, OrderRepository orders,
                              OrderItemRepository orderItems, CustomerOfferService offers, ShiftService shifts) {
        this(products, customers, orders, orderItems, offers, shifts, null, null);
    }
    public PosCheckoutService(ProductRepository products, CustomerRepository customers, OrderRepository orders,
                              OrderItemRepository orderItems, CustomerOfferService offers, ShiftService shifts,
                              LoyaltyService loyalty) {
        this(products, customers, orders, orderItems, offers, shifts, loyalty, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public PosCheckoutService(ProductRepository products, CustomerRepository customers, OrderRepository orders,
                              OrderItemRepository orderItems, CustomerOfferService offers, ShiftService shifts,
                              LoyaltyService loyalty, InventoryService inventory) {
        this.shifts = shifts;
        this.loyalty = loyalty;
        this.inventory = inventory;
        this.products = products;
        this.customers = customers;
        this.orders = orders;
        this.orderItems = orderItems;
        this.offers = offers;
    }

    private record Line(Product product, int quantity, BigDecimal unitPrice, boolean gift, boolean suggested) {}

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkout(CheckoutRequest request) {
        return checkout(request, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkout(CheckoutRequest request, Integer cashierId) {
        if (request.getPaymentMethod() != null && !Set.of("CASH", "BANK_TRANSFER", "CARD").contains(request.getPaymentMethod()))
            throw new IllegalArgumentException("Phương thức thanh toán không hợp lệ.");
        if (request.getItems() == null || request.getItems().isEmpty())
            throw new IllegalArgumentException("Hóa đơn đang trống.");
        // Every POS sale belongs to the seller's open shift so the end-of-shift report is complete.
        Integer shiftId = shifts == null || cashierId == null ? null : shifts.requireOpenShift(cashierId);
        Customer customer = request.getCustomerId() == null ? null : customers.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khách hàng đã chọn."));
        // L, R, F, M are measured before this invoice exists; the group after the sale adds exactly this invoice.
        CustomerOfferService.Lrfm before = customer == null ? null : offers.measure(customer);
        CustomerOfferService.Offer offer = customer == null ? null : offers.forCustomer(customer);
        Set<Integer> eligible = offer == null ? Set.of() : offer.giftProducts().stream().map(Product::getId).collect(Collectors.toSet());
        Map<Integer, Integer> quantities = new HashMap<>();
        int giftedUnits = 0;
        for (CheckoutRequest.ItemDto item : request.getItems()) {
            if (item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0 || item.getQuantity() > 10000)
                throw new IllegalArgumentException("Mã sản phẩm và số lượng phải hợp lệ.");
            quantities.merge(item.getProductId(), item.getQuantity(), Math::addExact);
            if (item.isGift()) {
                if (!eligible.contains(item.getProductId()))
                    throw new IllegalArgumentException("Sản phẩm tặng chưa khớp ưu đãi đã lưu cho khách hàng.");
                giftedUnits = Math.addExact(giftedUnits, item.getQuantity());
            }
        }
        if (giftedUnits > 0 && (offer == null || giftedUnits > offer.giftQuantityLimit()))
            throw new IllegalArgumentException("Số lượng quà vượt quá suất tặng của ưu đãi.");

        // Paid and gifted rows consume the same SKU stock. Lock once in ID order.
        // Only lots that have not expired can be sold (checked again under lock when the lots are taken).
        Map<Integer, Integer> sellable = inventory == null ? null : inventory.sellable();
        Map<Integer, Product> locked = new LinkedHashMap<>();
        quantities.keySet().stream().sorted().forEach(id -> {
            Product product = products.findByIdForUpdate(id)
                    .orElseThrow(() -> new IllegalArgumentException("Sản phẩm không còn trong danh mục: " + id));
            if (!Boolean.TRUE.equals(product.getIsActive()))
                throw new IllegalArgumentException("Sản phẩm đang tạm ngưng bán: " + product.getProductName());
            if (product.getStockQuantity() == null || product.getStockQuantity() < quantities.get(id))
                throw new IllegalArgumentException("Không đủ tồn kho cho cả phần mua và phần tặng: " + product.getProductName());
            if (sellable != null && sellable.getOrDefault(id, 0) < quantities.get(id))
                throw new IllegalArgumentException("Không đủ hàng còn hạn sử dụng: " + product.getProductName()
                        + " (còn " + sellable.getOrDefault(id, 0) + ").");
            if (product.getUnitPrice() == null || product.getUnitPrice().signum() < 0)
                throw new IllegalArgumentException("Giá sản phẩm chưa hợp lệ: " + product.getProductName());
            locked.put(id, product);
        });
        List<Line> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CheckoutRequest.ItemDto item : request.getItems()) {
            Product product = locked.get(item.getProductId());
            BigDecimal price = item.isGift() ? BigDecimal.ZERO.setScale(2) : product.getUnitPrice();
            lines.add(new Line(product, item.getQuantity(), price, item.isGift(), item.isFromSuggestion() && !item.isGift()));
            subtotal = subtotal.add(price.multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        if (giftedUnits > 0 && subtotal.signum() <= 0)
            throw new IllegalArgumentException("Quà tặng cần đi cùng sản phẩm mua trong hóa đơn.");
        BigDecimal rate = offer == null ? BigDecimal.ZERO : offer.discountPercent();
        BigDecimal percentDiscount = subtotal.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        // Money off comes only from the approved offer of the customer's group and from the customer's own points
        // (100 points = 10.000 đ, at most half of what is left after the offer); nobody can add money off at the counter.
        BigDecimal payable = subtotal.subtract(percentDiscount);
        int requestedPoints = request.getRedeemPoints() == null ? 0 : request.getRedeemPoints();
        LoyaltyService.Redemption redemption = LoyaltyService.Redemption.NONE;
        if (requestedPoints != 0) {
            if (customer == null || loyalty == null) throw new IllegalArgumentException("Chỉ khách thành viên mới dùng được điểm.");
            if (!loyalty.lockActiveCard(customer.getCustomerId()))
                throw new IllegalArgumentException("Khách chưa kích hoạt thẻ thành viên nên chưa dùng được điểm.");
            redemption = LoyaltyService.plan(requestedPoints, loyalty.balance(customer.getCustomerId()), payable);
        }
        BigDecimal discount = percentDiscount.add(redemption.value()), finalAmount = subtotal.subtract(discount);
        if ((request.getTotalAmount() != null && request.getTotalAmount().compareTo(subtotal) != 0) ||
                (request.getFinalAmount() != null && request.getFinalAmount().compareTo(finalAmount) != 0))
            throw new IllegalArgumentException("Giá hoặc ưu đãi đã thay đổi. Vui lòng cập nhật hóa đơn trước khi thanh toán.");

        LocalDateTime now = LocalDateTime.now();
        // The tier the customer had when buying (store spend of the 12 months before this invoice).
        LoyaltyService.TierStatus tier = customer == null || loyalty == null ? null : loyalty.tier(customer.getCustomerId(), now);
        String transactionNo = "HD" + now.format(DateTimeFormatter.ofPattern("yyMMddHHmmssSSS")) +
                UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Order order = new Order();
        order.setTransactionNo(transactionNo);
        order.setCustomerId(customer == null ? null : customer.getCustomerId());
        order.setCashierId(cashierId);
        order.setShiftId(shiftId);
        order.setOrderDate(now);
        order.setTotalAmount(subtotal);
        order.setDiscountAmount(discount);
        order.setFinalAmount(finalAmount);
        order.setPaymentMethod(request.getPaymentMethod() == null ? "CASH" : request.getPaymentMethod());
        order.setAppliedStrategyId(offer == null ? null : offer.strategyId());
        order.setPointsRedeemed(redemption.points());
        order.setPointsDiscount(redemption.value());
        orders.save(order);
        locked.forEach((id, product) -> {
            product.setStockQuantity(product.getStockQuantity() - quantities.get(id));
            products.save(product);
        });
        List<Map<String, Object>> receiptLines = new ArrayList<>();
        for (Line line : lines) {
            OrderItem item = new OrderItem();
            item.setTransactionNo(transactionNo);
            item.setProductId(line.product().getId());
            item.setQuantity(line.quantity());
            item.setUnitPrice(line.unitPrice());
            item.setListPrice(line.product().getUnitPrice());
            item.setRevenue(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
            item.setFromSuggestion(line.suggested());
            orderItems.save(item);
            if (inventory != null)   // earliest expiry first, expired lots skipped; which lots is kept for recalls
                inventory.allocate(item.getId(), line.product().getId(), line.quantity(), line.product().getProductName());
            receiptLines.add(Map.of("product_id", line.product().getId(), "product_name", line.product().getProductName(),
                    "quantity", line.quantity(), "unit_price", line.unitPrice(), "gift", line.gift(), "line_total", item.getRevenue()));
        }
        if (customer != null) {
            customer.setTotalOrders((customer.getTotalOrders() == null ? 0 : customer.getTotalOrders()) + 1);
            customer.setTotalSpent((customer.getTotalSpent() == null ? BigDecimal.ZERO : customer.getTotalSpent()).add(finalAmount));
            if (customer.getFirstPurchase() == null) customer.setFirstPurchase(now);
            customer.setLastPurchase(now);
            customer.setSegmentId(offers.classifyAfterPurchase(customer, before, finalAmount));
            customer.setSegmentScoredAt(now);
            customers.save(customer);
        }
        // Loyalty points, in the same transaction as the invoice: the points used first, then 1 point per 10.000 đ
        // actually paid × the tier multiplier.
        int pointsEarned = 0;
        Integer pointsBalance = null;
        if (customer != null && loyalty != null) {
            loyalty.redeem(customer.getCustomerId(), transactionNo, redemption, now);
            pointsEarned = loyalty.award(customer.getCustomerId(), transactionNo, finalAmount, now, tier.tier());
            pointsBalance = loyalty.balance(customer.getCustomerId());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("transaction_no", transactionNo);
        result.put("shift_id", shiftId);
        result.put("order_date", now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")));
        result.put("total_amount", subtotal);
        result.put("segment_discount_amount", percentDiscount);
        result.put("discount_amount", discount);
        result.put("final_amount", finalAmount);
        result.put("points_redeemed", redemption.points());
        result.put("points_discount", redemption.value());
        result.put("points_earned", pointsEarned);
        result.put("points_balance", pointsBalance);
        result.put("tier_label", tier == null ? null : tier.tier().label);
        result.put("points_multiplier", tier == null ? null : tier.tier().multiplier);
        result.put("items", receiptLines);
        result.put("message", "Đã lưu hóa đơn và trừ kho, bao gồm quà tặng giá 0 đồng.");
        return result;
    }
}
