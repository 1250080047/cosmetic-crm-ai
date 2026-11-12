package com.example.PhanTichAI.security;

import com.example.PhanTichAI.controller.MemberController;
import com.example.PhanTichAI.entity.Customer;
import com.example.PhanTichAI.repository.CustomerRepository;
import com.example.PhanTichAI.service.MemberQrService;
import com.example.PhanTichAI.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The member page is public, its changes need CSRF, and a signed-in customer never reaches staff pages. */
@WebMvcTest(controllers = MemberController.class)
@Import(SecurityConfig.class)
class MemberSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean AccountService accounts;
    @MockBean MemberService members;
    @MockBean MemberQrService qr;
    @MockBean CustomerRepository customers;

    private MockHttpSession signedIn() throws Exception {
        Customer customer = new Customer(); customer.setCustomerId(33627); customer.setCustomerCode("KH33627");
        when(members.login("0912345678", "246813")).thenReturn(customer);
        when(members.card(33627)).thenReturn(Map.of("customer_code", "KH33627", "points", 49));
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/member/login").session(session).with(csrf()).contentType("application/json")
                .content("{\"phone\":\"0912345678\",\"pin\":\"246813\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.card.points").value(49));
        return session;
    }

    @Test void memberPageIsOpenToEveryone() throws Exception {
        mvc.perform(get("/member")).andExpect(status().isOk()).andExpect(view().name("member"));
    }

    @Test void cardNeedsAMemberSessionAndAnswersInJson() throws Exception {
        mvc.perform(get("/api/member/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
        verify(members, never()).card(anyInt());
    }

    @Test void signingInWithoutCsrfIsRefused() throws Exception {
        mvc.perform(post("/api/member/login").contentType("application/json").content("{\"phone\":\"0912345678\",\"pin\":\"246813\"}"))
                .andExpect(status().isForbidden());
        verify(members, never()).login(any(), any());
    }

    @Test void signedInCustomerSeesOnlyTheirOwnCard() throws Exception {
        MockHttpSession session = signedIn();
        mvc.perform(get("/api/member/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.customer_code").value("KH33627"));
        verify(members, times(2)).card(33627);   // once in the sign-in answer, once here
        verify(members, never()).card(intThat(id -> id != 33627));
    }

    @Test void customerSessionNeverReachesThePosOrAdmin() throws Exception {
        MockHttpSession session = signedIn();
        mvc.perform(get("/api/pos/customer-lookup?q=KH24694").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/ai/model-status").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/pos").session(session)).andExpect(status().is4xxClientError());
    }

    @Test void wrongPinMessageComesBackToThePage() throws Exception {
        when(members.login(any(), any())).thenThrow(new MemberService.MemberException(401, "Số điện thoại hoặc PIN chưa đúng."));
        mvc.perform(post("/api/member/login").with(csrf()).contentType("application/json").content("{\"phone\":\"0912345678\",\"pin\":\"000001\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Số điện thoại hoặc PIN chưa đúng."));
    }
}
