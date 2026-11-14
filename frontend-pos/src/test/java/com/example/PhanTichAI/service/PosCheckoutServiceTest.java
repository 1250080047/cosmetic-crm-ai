package com.example.PhanTichAI.service;

import com.example.PhanTichAI.dto.CheckoutRequest;
import com.example.PhanTichAI.entity.*;
import com.example.PhanTichAI.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PosCheckoutServiceTest {
    private ProductRepository products;
    private CustomerRepository customers;
    private OrderRepository orders;
    private OrderItemRepository orderItems;
    private CustomerOfferService offers;
    private CustomerOfferService.Offer configuredOffer;
    private PosCheckoutService checkout;
    private ShiftService shifts;
    private Product cleanser;
    private CustomerOfferService.Lrfm classified;

    @BeforeEach
    void setUp() {
        products = mock(ProductRepository.class);
        customers = mock(CustomerRepository.class);
        orders = mock(OrderRepository.class);
        orderItems = mock(OrderItemRepository.class);
        offers = new CustomerOfferService(null, null, null) {
            @Override public Offer forCustomer(Customer customer) { return configuredOffer; }
            @Override public Lrfm measure(Customer customer) { return new Lrfm(200, 30, 2, 500000, true); }
            @Override public Integer classifyAfterPurchase(Customer customer, Lrfm before, BigDecimal amount) {
                classified = before.afterPurchase(amount); return 4; }
        };
        shifts = mock(ShiftService.class);
        checkout = new PosCheckoutService(products, customers, orders, orderItems, offers, shifts);
        cleanser = new Product();
        cleanser.setId(83);
        cleanser.setProductName("Sữa rửa mặt Cetaphil tạo bọt kiềm dầu ngừa mụn 237ml");
        cleanser.setUnitPrice(new BigDecimal("100000"));
        cleanser.setStockQuantity(5);
        cleanser.setIsActive(true);
    }

    private CheckoutRequest.ItemDto item(int quantity, boolean gift, String clientPrice) {
        var item = new CheckoutRequest.ItemDto();
        item.setProductId(83);
        item.setQuantity(quantity);
        item.setGift(gift);
        item.setUnitPrice(new BigDecimal(clientPrice));
        return item;
    }

    private CheckoutRequest request(CheckoutRequest.ItemDto... items) {
        var request = new CheckoutRequest();
        request.setItems(List.of(items));
        request.setPaymentMethod("CASH");
        return request;
    }

    private void configuredCustomer(CheckoutRequest request, List<Product> eligible, int limit) {
        Customer customer = new Customer();
        customer.setCustomerId(100);
        customer.setTotalOrders(2);
        customer.setTotalSpent(new BigDecimal("500000"));
        when(customers.findById(100)).thenReturn(Optional.of(customer));
        configuredOffer = new CustomerOfferService.Offer(
                42, 3, "Khách hàng ngủ đông", "#64748b", new BigDecimal("8"),
                "Tặng 1 sữa rửa mặt Cetaphil", "Lời tư vấn", eligible, limit, true);
        request.setCustomerId(100);
    }

    @Test
    void giftedCopyCostsZeroAndPurchasedCopyUsesCatalogPrice() {
        var request = request(item(1, false, "1"), item(1, true, "999999"));
        configuredCustomer(request, List.of(cleanser), 1);
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        request.setTotalAmount(new BigDecimal("100000"));
        request.setFinalAmount(new BigDecimal("92000"));

        Map<String, Object> result = checkout.checkout(request);

        assertThat((BigDecimal) result.get("total_amount")).isEqualByComparingTo("100000");
        assertThat((BigDecimal) result.get("final_amount")).isEqualByComparingTo("92000");
        assertThat(cleanser.getStockQuantity()).isEqualTo(3);
        var savedLines = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItems, times(2)).save(savedLines.capture());
        assertThat(savedLines.getAllValues().get(0).getUnitPrice()).isEqualByComparingTo("100000");
        assertThat(savedLines.getAllValues().get(1).getUnitPrice()).isEqualByComparingTo("0");
        assertThat(savedLines.getAllValues().get(1).getRevenue()).isEqualByComparingTo("0");
        var savedOrder = ArgumentCaptor.forClass(Order.class);
        verify(orders).save(savedOrder.capture());
        assertThat(savedOrder.getValue().getAppliedStrategyId()).isEqualTo(42);
    }

    @Test
    void sellerWithoutAnOpenShiftCannotCheckOutAndNothingIsWritten() {
        when(shifts.requireOpenShift(7)).thenThrow(new IllegalArgumentException("Bạn chưa mở ca. Bấm “Mở ca” trước khi bán hàng."));
        assertThatThrownBy(() -> checkout.checkout(request(item(1, false, "1")), 7))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mở ca");
        verifyNoInteractions(orders, orderItems);
    }

    @Test
    void saleIsRecordedInTheSellersShiftAndGiftKeepsItsListPrice() {
        when(shifts.requireOpenShift(7)).thenReturn(15);
        var request = request(item(1, false, "1"), item(1, true, "0"));
        configuredCustomer(request, List.of(cleanser), 1);
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        Map<String, Object> result = checkout.checkout(request, 7);
        assertThat(result.get("shift_id")).isEqualTo(15);
        var savedOrder = ArgumentCaptor.forClass(Order.class);
        verify(orders).save(savedOrder.capture());
        assertThat(savedOrder.getValue().getShiftId()).isEqualTo(15);
        assertThat(savedOrder.getValue().getCashierId()).isEqualTo(7);
        var savedLines = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItems, times(2)).save(savedLines.capture());
        assertThat(savedLines.getAllValues().get(1).getUnitPrice()).isEqualByComparingTo("0");
        assertThat(savedLines.getAllValues().get(1).getListPrice()).isEqualByComparingTo("100000");
    }

    @Test
    void purchasedAndGiftedQuantitiesShareTheStockLimit() {
        cleanser.setStockQuantity(1);
        var request = request(item(1, false, "100000"), item(1, true, "0"));
        configuredCustomer(request, List.of(cleanser), 1);
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        assertThatThrownBy(() -> checkout.checkout(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tồn kho");
        verify(orders, never()).save(any());
        verify(products, never()).save(any());
    }

    @Test
    void cannotMarkAnUnconfiguredProductAsAFreeGift() {
        var request = request(item(1, false, "100000"), item(1, true, "0"));
        configuredCustomer(request, List.of(), 1);
        assertThatThrownBy(() -> checkout.checkout(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chưa khớp");
        verifyNoInteractions(orders, orderItems);
    }

    @Test
    void cannotExceedTheConfiguredGiftQuantity() {
        var request = request(item(1, false, "100000"), item(2, true, "0"));
        configuredCustomer(request, List.of(cleanser), 1);
        assertThatThrownBy(() -> checkout.checkout(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("suất tặng");
        verifyNoInteractions(orders, orderItems);
    }

    @Test
    void rejectsZeroAndNegativeQuantitiesBeforeWriting() {
        var request = request(item(0, false, "100000"));
        assertThatThrownBy(() -> checkout.checkout(request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orders, orderItems, products);
    }

    @Test
    void clientCannotMakeAnOrdinaryProductFreeBySendingZeroPrice() {
        var request = request(item(1, false, "0"));
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        var result = checkout.checkout(request);
        assertThat((BigDecimal) result.get("final_amount")).isEqualByComparingTo("100000");
    }

    @Test
    void staleOrTamperedDisplayedTotalsAreRejectedBeforeWrites() {
        var request = request(item(1, false, "1"));
        request.setFinalAmount(new BigDecimal("1"));
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        assertThatThrownBy(() -> checkout.checkout(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("đã thay đổi");
        verifyNoInteractions(orders, orderItems);
    }

    @Test
    void onlyTheApprovedOfferDiscountsAnInvoice() {
        var request = request(item(1, false, "100000"));
        configuredCustomer(request, List.of(), 1);
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        var result = checkout.checkout(request);
        assertThat((BigDecimal) result.get("discount_amount")).isEqualByComparingTo("8000");
        assertThat((BigDecimal) result.get("final_amount")).isEqualByComparingTo("92000");
        assertThat(result).doesNotContainKey("voucher_amount");
    }

    @Test
    void identifiedCustomerEarnsOnePointPerTenThousandPaid() {
        var points = mock(LoyaltyPointRepository.class);
        when(points.balanceOf(100)).thenReturn(58L);
        checkout = new PosCheckoutService(products, customers, orders, orderItems, offers, shifts, new LoyaltyService(points));
        var request = request(item(1, false, "100000"));
        configuredCustomer(request, List.of(), 1);
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        var result = checkout.checkout(request);
        var saved = ArgumentCaptor.forClass(LoyaltyPoint.class);
        verify(points).save(saved.capture());
        assertThat(saved.getValue().getPoints()).isEqualTo(9);              // 92.000 đ paid after the 8% offer
        assertThat(saved.getValue().getCustomerId()).isEqualTo(100);
        assertThat(saved.getValue().getTransactionNo()).isEqualTo(result.get("transaction_no"));
        assertThat(result.get("points_earned")).isEqualTo(9);
        assertThat(result.get("points_balance")).isEqualTo(58);
    }

    @Test
    void walkInSaleEarnsNoPoints() {
        var points = mock(LoyaltyPointRepository.class);
        checkout = new PosCheckoutService(products, customers, orders, orderItems, offers, shifts, new LoyaltyService(points));
        var request = request(item(1, false, "100000"));
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        var result = checkout.checkout(request);
        verifyNoInteractions(points);
        assertThat(result.get("points_earned")).isEqualTo(0);
        assertThat(result.get("points_balance")).isNull();
    }

    @Test
    void pointsRoundDown() {
        assertThat(LoyaltyService.pointsFor(new BigDecimal("9999"))).isZero();
        assertThat(LoyaltyService.pointsFor(new BigDecimal("10000"))).isEqualTo(1);
        assertThat(LoyaltyService.pointsFor(new BigDecimal("196650"))).isEqualTo(19);
        assertThat(LoyaltyService.pointsFor(BigDecimal.ZERO)).isZero();
        assertThat(LoyaltyService.pointsFor(null)).isZero();
    }

    // ---- tiers and redeeming points ---------------------------------------------------------------
    @Test
    void tiersFollowTwelveMonthStoreSpend() {
        assertThat(LoyaltyService.Tier.of(new BigDecimal("1999999"))).isEqualTo(LoyaltyService.Tier.MEMBER);
        assertThat(LoyaltyService.Tier.of(new BigDecimal("2000000"))).isEqualTo(LoyaltyService.Tier.SILVER);
        assertThat(LoyaltyService.Tier.of(new BigDecimal("5000000"))).isEqualTo(LoyaltyService.Tier.GOLD);
        assertThat(LoyaltyService.pointsFor(new BigDecimal("100000"), LoyaltyService.Tier.SILVER)).isEqualTo(15);
        assertThat(LoyaltyService.pointsFor(new BigDecimal("100000"), LoyaltyService.Tier.GOLD)).isEqualTo(20);
        var status = LoyaltyService.TierStatus.of(new BigDecimal("3800000"));
        assertThat(status.tier()).isEqualTo(LoyaltyService.Tier.SILVER);
        assertThat(status.toNext()).isEqualByComparingTo("1200000");
    }

    @Test
    void pointsAreUsedInStepsOfOneHundredUpToHalfTheInvoice() {
        assertThat(LoyaltyService.maxRedeemable(49, new BigDecimal("92000"))).isZero();
        assertThat(LoyaltyService.maxRedeemable(250, new BigDecimal("92000"))).isEqualTo(200);   // balance limits
        assertThat(LoyaltyService.maxRedeemable(1000, new BigDecimal("92000"))).isEqualTo(400);  // 50% of 92.000 đ limits
        assertThat(LoyaltyService.plan(200, 250, new BigDecimal("92000")).value()).isEqualByComparingTo("20000");
        assertThatThrownBy(() -> LoyaltyService.plan(150, 250, new BigDecimal("92000"))).hasMessageContaining("từng 100 điểm");
        assertThatThrownBy(() -> LoyaltyService.plan(300, 250, new BigDecimal("92000"))).hasMessageContaining("chỉ còn");
        assertThatThrownBy(() -> LoyaltyService.plan(500, 1000, new BigDecimal("92000"))).hasMessageContaining("50%");
        assertThatThrownBy(() -> LoyaltyService.plan(-100, 1000, new BigDecimal("92000"))).isInstanceOf(IllegalArgumentException.class);
    }

    private LoyaltyService loyaltyWith(LoyaltyPointRepository points, boolean activeCard, String spend12m) {
        return new LoyaltyService(points) {
            @Override public boolean lockActiveCard(int customerId) { return activeCard; }
            @Override public TierStatus tier(int customerId, java.time.LocalDateTime at) { return TierStatus.of(new BigDecimal(spend12m)); }
        };
    }

    @Test
    void silverMemberUsesPointsAndEarnsOnWhatIsLeftToPay() {
        var points = mock(LoyaltyPointRepository.class);
        when(points.balanceOf(100)).thenReturn(250L);
        checkout = new PosCheckoutService(products, customers, orders, orderItems, offers, shifts, loyaltyWith(points, true, "2500000"));
        var request = request(item(1, false, "100000"));
        configuredCustomer(request, List.of(), 1);
        request.setRedeemPoints(200);
        request.setTotalAmount(new BigDecimal("100000"));
        request.setFinalAmount(new BigDecimal("72000"));       // 100.000 − 8% offer − 200 points (20.000 đ)
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        var result = checkout.checkout(request);
        assertThat((BigDecimal) result.get("final_amount")).isEqualByComparingTo("72000");
        assertThat((BigDecimal) result.get("discount_amount")).isEqualByComparingTo("28000");
        assertThat(result.get("points_redeemed")).isEqualTo(200);
        var saved = ArgumentCaptor.forClass(LoyaltyPoint.class);
        verify(points, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(LoyaltyPoint::getKind, LoyaltyPoint::getPoints)
                .containsExactly(tuple("REDEEM", -200), tuple("EARN", 10));     // 72.000 đ × 1,5 (Bạc) = 10 points
        var order = ArgumentCaptor.forClass(Order.class);
        verify(orders).save(order.capture());
        assertThat(order.getValue().getPointsRedeemed()).isEqualTo(200);
        assertThat(order.getValue().getPointsDiscount()).isEqualByComparingTo("20000");
    }

    @Test
    void pointsNeedAnActivatedCard() {
        var points = mock(LoyaltyPointRepository.class);
        when(points.balanceOf(100)).thenReturn(250L);
        checkout = new PosCheckoutService(products, customers, orders, orderItems, offers, shifts, loyaltyWith(points, false, "0"));
        var request = request(item(1, false, "100000"));
        configuredCustomer(request, List.of(), 1);
        request.setRedeemPoints(100);
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        assertThatThrownBy(() -> checkout.checkout(request)).hasMessageContaining("chưa kích hoạt");
        verifyNoInteractions(orders, orderItems);
        verify(points, never()).save(any());
    }

    @Test
    void walkInCustomerCannotUsePoints() {
        var request = request(item(1, false, "100000"));
        request.setRedeemPoints(100);
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        assertThatThrownBy(() -> checkout.checkout(request)).hasMessageContaining("thành viên");
    }

    @Test
    void aBrowserAskingForExtraMoneyOffIsRejected() {
        var request = request(item(1, false, "100000"));
        configuredCustomer(request, List.of(), 1);
        request.setTotalAmount(new BigDecimal("100000"));
        request.setDiscountAmount(new BigDecimal("58000"));     // 8% offer + 50.000 đ asked by an old page
        request.setFinalAmount(new BigDecimal("42000"));
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        assertThatThrownBy(() -> checkout.checkout(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("đã thay đổi");
        verifyNoInteractions(orders, orderItems);
    }

    @Test
    void giftRequiresAPurchasedProductOnTheInvoice() {
        var request = request(item(1, true, "0"));
        configuredCustomer(request, List.of(cleanser), 1);
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        assertThatThrownBy(() -> checkout.checkout(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("đi cùng");
        verifyNoInteractions(orders, orderItems);
    }

    @Test
    void updatedRfmChangesStoredGroupWithoutChangingTheCurrentInvoiceOffer() {
        var request = request(item(1, false, "100000"));
        configuredCustomer(request, List.of(), 1);
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        var result = checkout.checkout(request);
        assertThat((BigDecimal) result.get("final_amount")).isEqualByComparingTo("92000");
        var savedCustomer = ArgumentCaptor.forClass(Customer.class);
        verify(customers).save(savedCustomer.capture());
        assertThat(savedCustomer.getValue().getSegmentId()).isEqualTo(4);
        assertThat(classified).isEqualTo(new CustomerOfferService.Lrfm(200, 0, 3, 592000, true));
        assertThat(savedCustomer.getValue().getFirstPurchase()).isNotNull();
        assertThat(savedCustomer.getValue().getSegmentScoredAt()).isNotNull();
        assertThat(savedCustomer.getValue().getTotalOrders()).isEqualTo(3);
        assertThat(savedCustomer.getValue().getTotalSpent()).isEqualByComparingTo("592000");
        var savedOrder = ArgumentCaptor.forClass(Order.class);
        verify(orders).save(savedOrder.capture());
        assertThat(savedOrder.getValue().getAppliedStrategyId()).isEqualTo(42);
    }

    @Test
    void persistenceFailureEscapesTheTransactionalService() {
        var request = request(item(1, false, "100000"));
        when(products.findByIdForUpdate(83)).thenReturn(Optional.of(cleanser));
        when(orderItems.save(any())).thenThrow(new IllegalStateException("write failed"));
        assertThatThrownBy(() -> checkout.checkout(request)).isInstanceOf(IllegalStateException.class).hasMessage("write failed");
        verify(customers, never()).save(any());
    }
}
