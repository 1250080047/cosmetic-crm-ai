package com.example.PhanTichAI.service;

import com.example.PhanTichAI.entity.Customer;
import com.example.PhanTichAI.entity.MemberAccount;
import com.example.PhanTichAI.repository.CustomerRepository;
import com.example.PhanTichAI.repository.MemberAccountRepository;
import com.example.PhanTichAI.service.MemberService.MemberException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemberServiceTest {
    /** A clock the test can move forward. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-12T03:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }

    private final Map<Integer, MemberAccount> stored = new HashMap<>();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private CustomerRepository customers;
    private CustomerRegistrationService registration;
    private TestClock clock;
    private MemberService members;
    private Customer known;

    @BeforeEach void setUp() {
        var accounts = mock(MemberAccountRepository.class);
        when(accounts.findById(anyInt())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<Integer>getArgument(0))));
        when(accounts.findByIdForUpdate(anyInt())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<Integer>getArgument(0))));
        when(accounts.save(any())).thenAnswer(call -> { MemberAccount a = call.getArgument(0); stored.put(a.getCustomerId(), a); return a; });
        customers = mock(CustomerRepository.class);
        registration = mock(CustomerRegistrationService.class);
        clock = new TestClock();
        members = new MemberService(accounts, customers, registration, mock(LoyaltyService.class), encoder, mock(JdbcTemplate.class), clock);
        known = new Customer();
        known.setCustomerId(33627); known.setCustomerCode("KH33627"); known.setPhone("0912345678");
        when(customers.findByPhone("0912345678")).thenReturn(Optional.of(known));
        when(customers.findById(33627)).thenReturn(Optional.of(known));
    }

    private int status(Runnable action) {
        try { action.run(); } catch (MemberException error) { return error.status(); }
        return 200;
    }

    @Test void newPhoneRegistersWithAHashedPin() {
        Customer created = new Customer(); created.setCustomerId(40000); created.setCustomerCode("KH40000");
        when(customers.findByPhone("0987654321")).thenReturn(Optional.empty());
        when(registration.register("0987654321", "Nguyễn Phương Anh")).thenReturn(created);
        assertThat(members.register(" Nguyễn Phương Anh ", "0987654321", "246813")).isSameAs(created);
        MemberAccount account = stored.get(40000);
        assertThat(account.getPinHash()).isNotEqualTo("246813");
        assertThat(encoder.matches("246813", account.getPinHash())).isTrue();
    }

    @Test void weakOrMalformedPinsAreRefused() {
        when(customers.findByPhone("0987654321")).thenReturn(Optional.empty());
        for (String pin : new String[]{"123456", "111111", "654321", "12345", "12a456", null})
            assertThat(status(() -> members.register("Lan Anh", "0987654321", pin))).as(String.valueOf(pin)).isEqualTo(400);
        verifyNoInteractions(registration);
    }

    @Test void knownPhoneMustActivateInsteadOfRegistering() {
        assertThatThrownBy(() -> members.register("Ai đó", "0912345678", "246813"))
                .isInstanceOf(MemberException.class).hasMessageContaining("mã kích hoạt");
        assertThat(status(() -> members.login("0912345678", "246813"))).isEqualTo(403);   // no card yet
        verifyNoInteractions(registration);
    }

    @Test void activationCodeIsOneTimeAndExpiresAfterTenMinutes() {
        var issued = members.issueActivationCode(33627);
        assertThat(issued.code()).matches("\\d{6}");
        assertThat(issued.resetsPin()).isFalse();
        String wrong = issued.code().equals("000000") ? "000001" : "000000";
        assertThat(status(() -> members.activate("0912345678", wrong, "246813"))).isEqualTo(400);
        assertThat(members.activate("0912345678", issued.code(), "246813")).isSameAs(known);
        assertThat(stored.get(33627).getActivationCodeHash()).isNull();
        assertThat(status(() -> members.activate("0912345678", issued.code(), "975318"))).isEqualTo(400);   // used once
        assertThat(members.login("0912345678", "246813")).isSameAs(known);

        var reset = members.issueActivationCode(33627);
        assertThat(reset.resetsPin()).isTrue();
        clock.advance(Duration.ofMinutes(11));
        assertThat(status(() -> members.activate("0912345678", reset.code(), "975318"))).isEqualTo(400);    // expired
    }

    @Test void fiveWrongCodesCancelTheActivationCode() {
        var issued = members.issueActivationCode(33627);
        String wrong = issued.code().equals("000000") ? "000001" : "000000";
        for (int i = 0; i < MemberService.MAX_FAILED; i++)
            assertThat(status(() -> members.activate("0912345678", wrong, "246813"))).isEqualTo(400);
        assertThat(stored.get(33627).getActivationCodeHash()).isNull();
        assertThat(status(() -> members.activate("0912345678", issued.code(), "246813"))).isEqualTo(400);
    }

    private void withPin(String pin) {
        MemberAccount account = new MemberAccount(33627, LocalDateTime.now(clock));
        account.setPinHash(encoder.encode(pin));
        stored.put(33627, account);
    }

    @Test void fiveWrongPinsLockTheCardForFifteenMinutes() {
        withPin("246813");
        for (int i = 1; i < MemberService.MAX_FAILED; i++) assertThat(status(() -> members.login("0912345678", "999998"))).isEqualTo(401);
        assertThat(status(() -> members.login("0912345678", "999998"))).isEqualTo(429);
        assertThat(status(() -> members.login("0912345678", "246813"))).isEqualTo(429);   // even the right PIN waits
        clock.advance(Duration.ofMinutes(16));
        assertThat(members.login("0912345678", "246813")).isSameAs(known);
        assertThat(stored.get(33627).getFailedAttempts()).isZero();
    }

    @Test void unknownPhoneGetsTheSameAnswerAsAWrongPin() {
        withPin("246813");
        when(customers.findByPhone("0900000000")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> members.login("0900000000", "246813")).hasMessage("Số điện thoại hoặc PIN chưa đúng.");
        assertThatThrownBy(() -> members.login("0912345678", "999998")).hasMessage("Số điện thoại hoặc PIN chưa đúng.");
    }

    @Test void phoneIsMaskedOnTheCard() {
        assertThat(MemberService.maskPhone("0912345678")).isEqualTo("0912•••678");
    }
}
