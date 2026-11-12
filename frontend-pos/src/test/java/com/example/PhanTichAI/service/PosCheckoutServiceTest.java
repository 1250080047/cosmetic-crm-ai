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
