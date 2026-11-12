package com.example.PhanTichAI.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MemberQrServiceTest {
    private static final byte[] KEY = "a-test-key-that-is-long-enough-for-hmac-sha256".getBytes(StandardCharsets.UTF_8);
    private static final Instant T0 = Instant.parse("2026-11-12T08:00:10Z");

    private static MemberQrService at(Instant instant) { return new MemberQrService(KEY, Clock.fixed(instant, ZoneOffset.UTC)); }

    @Test void codeIsValidThisMinuteAndTheNextThenExpires() {
        var token = at(T0).issue("KH33627");
        assertThat(token.value()).matches("CM1\\.KH33627\\.\\d+\\.[0-9A-F]{20}");
        assertThat(token.secondsLeft()).isEqualTo(50);
        assertThat(at(T0).verify(token.value())).isEqualTo(new MemberQrService.Check(MemberQrService.Status.VALID, "KH33627"));
        assertThat(at(T0.plusSeconds(100)).verify(token.value()).status()).isEqualTo(MemberQrService.Status.VALID);
        assertThat(at(T0.plusSeconds(130)).verify(token.value()).status()).isEqualTo(MemberQrService.Status.EXPIRED);
    }

    @Test void forgedOrEditedCodesAreRejected() {
        String token = at(T0).issue("KH33627").value();
        String otherCustomer = token.replace("KH33627", "KH33628");
        String editedSignature = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
        long step = T0.getEpochSecond() / 60;
        String future = token.replace("." + step + ".", "." + (step + 5) + ".");
        for (String forged : new String[]{otherCustomer, editedSignature, future, "CM1.KH33627.1.ABC", "KH33627", ""})
            assertThat(at(T0).verify(forged).status()).as(forged).isEqualTo(MemberQrService.Status.INVALID);
        // another server key cannot make codes for this store
        var otherStore = new MemberQrService("another-key-that-is-long-enough-for-hmac-sha256".getBytes(StandardCharsets.UTF_8),
                Clock.fixed(T0, ZoneOffset.UTC));
        assertThat(at(T0).verify(otherStore.issue("KH33627").value()).status()).isEqualTo(MemberQrService.Status.INVALID);
    }

    @Test void scannerTextIsAcceptedInAnyCase() {
        String token = at(T0).issue("KH24694").value();
        assertThat(at(T0).verify(token.toLowerCase()).customerCode()).isEqualTo("KH24694");
        assertThat(MemberQrService.looksLikeToken(token.toLowerCase())).isTrue();
        assertThat(MemberQrService.looksLikeToken("KH24694")).isFalse();
    }
}
