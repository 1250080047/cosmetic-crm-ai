package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.entity.Customer;
import com.example.PhanTichAI.repository.CustomerRepository;
import com.example.PhanTichAI.service.CustomerOfferService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PosCustomerRegistrationTest {
    @Test void newMemberStaysOutsideTheGroupsUntilTheFirstInvoice() {
        var customers=mock(CustomerRepository.class);var offers=mock(CustomerOfferService.class);
        var controller=new PosApiController(customers,mock(com.example.PhanTichAI.repository.ProductRepository.class),offers,
                mock(com.example.PhanTichAI.service.PosCheckoutService.class),mock(com.example.PhanTichAI.service.ShiftService.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),new com.example.PhanTichAI.service.CustomerRegistrationService(customers),
                mock(com.example.PhanTichAI.service.MemberService.class),mock(com.example.PhanTichAI.service.MemberQrService.class),
                mock(com.example.PhanTichAI.service.LoyaltyService.class),mock(com.example.PhanTichAI.service.MemberLinkService.class));
        when(customers.findByPhone(anyString())).thenReturn(Optional.empty());
        when(customers.maxCustomerId()).thenReturn(4362);
        var input=new PosApiController.CreateCustomerRequest();input.setPhone("0912345678");input.setFullName("Khách hàng mới");
        assertThat(controller.createCustomer(input).getStatusCode().value()).isEqualTo(200);
        var captured=ArgumentCaptor.forClass(Customer.class);verify(customers).save(captured.capture());
        assertThat(captured.getValue().getSegmentId()).isNull();
        assertThat(captured.getValue().getFirstPurchase()).isNull();
        assertThat(captured.getValue().getTotalOrders()).isZero();
        assertThat(captured.getValue().getCustomerId()).isEqualTo(4363);
        assertThat(captured.getValue().getCustomerCode()).isEqualTo("KH04363");
        verifyNoInteractions(offers);
    }
}
