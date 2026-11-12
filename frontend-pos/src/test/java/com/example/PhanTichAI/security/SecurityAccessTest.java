package com.example.PhanTichAI.security;

import com.example.PhanTichAI.controller.*;
import com.example.PhanTichAI.service.RevenueService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={AccountController.class, RevenueController.class})
@Import(SecurityConfig.class)
class SecurityAccessTest {
    @Autowired MockMvc mvc;
    @MockBean AccountService accounts;
    @MockBean RevenueService revenue;
    private StoreUser user(String role) {
        StoreUser u=new StoreUser(7,"tester","$2a$10$hash","Người kiểm thử",role,true);
        when(accounts.loadUserByUsername("tester")).thenReturn(u); return u;
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor as(String role) {
        StoreUser u=user(role);return authentication(new UsernamePasswordAuthenticationToken(u,null,u.getAuthorities()));
    }
    @Test void anonymousCannotReadRevenueOrUserList() throws Exception {
        mvc.perform(get("/api/admin/revenue?year=2025")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        verifyNoInteractions(revenue);
    }
    @Test void cashierCannotReadBusinessOrManageAccounts() throws Exception {
        mvc.perform(get("/api/admin/revenue?year=2025").with(as("CASHIER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users").with(as("CASHIER")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(revenue);
    }
    @Test void managerReadsRevenueButCannotManageAccounts() throws Exception {
        when(revenue.report("month",2025,1,"year")).thenReturn(Map.of("success",true));
        mvc.perform(get("/api/admin/revenue?year=2025").with(as("MANAGER"))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").with(as("MANAGER"))).andExpect(status().isForbidden());
        verify(revenue).report("month",2025,1,"year");
    }
    @Test void adminMutationRequiresCsrf() throws Exception {
        mvc.perform(post("/api/admin/users").with(as("ADMIN")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verify(accounts,never()).save(any(),any(),any());
        mvc.perform(post("/api/admin/users").with(as("ADMIN")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isOk());
        verify(accounts).save(isNull(),any(),any());
    }
    @Test void accountResponseNeverExposesPasswordHash() throws Exception {
        mvc.perform(get("/api/auth/me").with(as("CASHIER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CASHIER")).andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.password_hash").doesNotExist());
    }
    @Test void disablingAccountRevokesExistingAccess() throws Exception {
        var auth=as("ADMIN");
        when(accounts.loadUserByUsername("tester")).thenReturn(new StoreUser(7,"tester","$2a$10$hash","Tên","ADMIN",false));
        mvc.perform(get("/api/admin/users").with(auth)).andExpect(status().isUnauthorized());
    }
    @Test void changedRoleIsAppliedToExistingSession() throws Exception {
        var auth=as("ADMIN");user("CASHIER");
        mvc.perform(get("/api/admin/users").with(auth)).andExpect(status().isForbidden());
    }
    @Test void passwordResetRevokesOldSession() throws Exception {
        var auth=as("ADMIN");
        when(accounts.loadUserByUsername("tester")).thenReturn(new StoreUser(7,"tester","different-hash","Tên","ADMIN",true));
        mvc.perform(get("/api/admin/users").with(auth)).andExpect(status().isUnauthorized());
    }
    @Test void authenticationSurvivesSpringErasingPasswordCredentials() throws Exception {
        var auth=as("ADMIN");
        // Real form login erases User.password before storing the principal in the session.
        var erased=user("ADMIN"); erased.eraseCredentials();
        var token=new UsernamePasswordAuthenticationToken(erased,null,erased.getAuthorities());
        user("ADMIN");
        mvc.perform(get("/api/auth/me").with(authentication(token))).andExpect(status().isOk());
    }
}
