package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.dto.CheckoutRequest;
import com.example.PhanTichAI.security.StoreUser;
import com.example.PhanTichAI.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Points can be spent only right after the cashier scanned the rotating QR code on the customer's own phone. */
class PosPointsPresenceTest {
    private PosCheckoutService checkout;
    private PosApiController controller;
    private final StoreUser cashier = new StoreUser(7, "nhanvien1", "x", "Nhân viên 1", "CASHIER", true);

    @BeforeEach void setUp() {
        checkout = mock(PosCheckoutService.class);
        when(checkout.checkout(any(), anyInt())).thenReturn(Map.of("success", true));
        controller = new PosApiController(mock(com.example.PhanTichAI.repository.CustomerRepository.class),
                mock(com.example.PhanTichAI.repository.ProductRepository.class), mock(CustomerOfferService.class), checkout,
                mock(ShiftService.class), mock(org.springframework.jdbc.core.JdbcTemplate.class), mock(CustomerRegistrationService.class),
                mock(MemberService.class), mock(MemberQrService.class), mock(LoyaltyService.class), mock(MemberLinkService.class));
    }

    private CheckoutRequest usingPoints(int customerId) {
        CheckoutRequest request = new CheckoutRequest();
        request.setCustomerId(customerId);
        request.setRedeemPoints(100);
        request.setItems(List.of(new CheckoutRequest.ItemDto()));
        return request;
    }

    @Test void typingAPhoneNumberIsNotEnoughToSpendPoints() {
        var response = controller.checkout(usingPoints(100), cashier, new MockHttpSession());
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(checkout);
    }

    @Test void aScanOfAnotherCustomerDoesNotCount() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PosApiController.VERIFIED_MEMBER, new PosApiController.VerifiedMember(200, Instant.now()));
        assertThat(controller.checkout(usingPoints(100), cashier, session).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(checkout);
    }

    @Test void aScanOlderThanFifteenMinutesDoesNotCount() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PosApiController.VERIFIED_MEMBER, new PosApiController.VerifiedMember(100, Instant.now().minusSeconds(16 * 60)));
        assertThat(controller.checkout(usingPoints(100), cashier, session).getStatusCode().value()).isEqualTo(403);
    }

    @Test void afterAScanThePointsArePassedOnOnce() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PosApiController.VERIFIED_MEMBER, new PosApiController.VerifiedMember(100, Instant.now()));
        assertThat(controller.checkout(usingPoints(100), cashier, session).getStatusCode().value()).isEqualTo(200);
        verify(checkout).checkout(any(), eq(7));
        assertThat(session.getAttribute(PosApiController.VERIFIED_MEMBER)).as("one scan, one invoice").isNull();
    }

    @Test void invoicesWithoutPointsNeedNoScan() {
        CheckoutRequest request = usingPoints(100);
        request.setRedeemPoints(null);
        assertThat(controller.checkout(request, cashier, new MockHttpSession()).getStatusCode().value()).isEqualTo(200);
    }
}
