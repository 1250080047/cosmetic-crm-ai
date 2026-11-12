package com.example.PhanTichAI.security;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AccountServiceTest {
    private JdbcTemplate jdbc;
    private AccountService accounts;
    @BeforeEach void setup() {
        jdbc=mock(JdbcTemplate.class);accounts=new AccountService(jdbc,new BCryptPasswordEncoder());
        when(jdbc.queryForList(contains("SELECT id FROM roles"),eq(Integer.class),any())).thenReturn(List.of(1));
        when(jdbc.queryForList(contains("SELECT u.is_active"),anyInt())).thenReturn(List.of(Map.of("is_active",true,"name","ADMIN")));
        when(jdbc.queryForObject(contains("UPPER(r.name)='ADMIN'"),eq(Integer.class))).thenReturn(1);
    }
    @Test void cannotDisableTheLastActiveAdmin() {
        assertThatThrownBy(()->accounts.save(1,new AccountService.AccountInput(null,"Tên",null,"CASHIER",true),null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("ít nhất một");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void cannotLockYourOwnAccountEvenWhenThereAreOtherAdmins() {
        var actor=new StoreUser(1,"admin","hash","Tên","ADMIN",true);
        assertThatThrownBy(()->accounts.save(1,new AccountService.AccountInput(null,"Tên",null,"ADMIN",false),actor))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Không thể tự khóa");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void rejectsUnknownRolesAndWeakPasswordsBeforeWriting() {
        assertThatThrownBy(()->accounts.save(null,new AccountService.AccountInput("cashier","Tên","short","CASHIER",true),null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->accounts.save(null,new AccountService.AccountInput("cashier","Tên","strong-password","SUPERUSER",true),null))
                .isInstanceOf(ResponseStatusException.class);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void accountInputDoesNotExposePasswordInLogs() {
        assertThat(new AccountService.AccountInput("name","Tên","secret-value","ADMIN",true).toString()).doesNotContain("secret-value");
    }
}
