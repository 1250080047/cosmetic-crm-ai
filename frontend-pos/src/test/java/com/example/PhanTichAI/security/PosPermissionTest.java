package com.example.PhanTichAI.security;

import com.example.PhanTichAI.controller.PosApiController;
import com.example.PhanTichAI.entity.Product;
import com.example.PhanTichAI.repository.ProductRepository;
import com.example.PhanTichAI.service.PosCheckoutService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PosPermissionTest {
    @Test void cashierCatalogExcludesCostPriceAndInactiveProducts() {
        var products=mock(ProductRepository.class);
        var controller=new PosApiController(mock(com.example.PhanTichAI.repository.CustomerRepository.class),products,
                mock(com.example.PhanTichAI.service.CustomerOfferService.class),mock(PosCheckoutService.class),
                mock(com.example.PhanTichAI.service.ShiftService.class),mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(com.example.PhanTichAI.service.CustomerRegistrationService.class),mock(com.example.PhanTichAI.service.MemberService.class),
                mock(com.example.PhanTichAI.service.MemberQrService.class),mock(com.example.PhanTichAI.service.LoyaltyService.class),
                mock(com.example.PhanTichAI.service.MemberLinkService.class));
        Product active=new Product();active.setId(1);active.setCostPrice(new BigDecimal("123"));
        Product inactive=new Product();inactive.setId(2);inactive.setIsActive(false);
        when(products.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(active));
        var body=(List<?>)controller.getAllProducts();assertThat(body).hasSize(1);
        verify(products,never()).findAll();
        assertThat(((Map<?,?>)body.get(0)).containsKey("costPrice")).isFalse();
    }
}
