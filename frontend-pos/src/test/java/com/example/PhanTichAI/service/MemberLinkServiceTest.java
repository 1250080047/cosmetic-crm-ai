package com.example.PhanTichAI.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MemberLinkServiceTest {
    private static MockHttpServletRequest pos() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/pos/member-link");
        request.setServerName("localhost"); request.setServerPort(8080); request.setScheme("http");
        return request;
    }

    @Test void runningTunnelGivesAnAddressForAnyNetwork() {
        var link = new MemberLinkService("", () -> Optional.of("calm-river-1234.trycloudflare.com")).link(pos());
        assertThat(link.url()).isEqualTo("https://calm-river-1234.trycloudflare.com/member");
        assertThat(link.scope()).isEqualTo(MemberLinkService.INTERNET);
        assertThat(link.reachableFromPhones()).isTrue();
    }

    @Test void withoutTunnelOnlyTheShopWifiAddressIsOffered() {
        var link = new MemberLinkService("", Optional::empty).link(pos());
        assertThat(link.scope()).isIn(MemberLinkService.WIFI, MemberLinkService.LOCAL_ONLY);
        assertThat(link.url()).startsWith("http://").endsWith(":8080/member");
    }

    @Test void fixedAddressWins() {
        var link = new MemberLinkService("https://the.cuahang.id.vn/", () -> Optional.of("x.trycloudflare.com")).link(pos());
        assertThat(link.url()).isEqualTo("https://the.cuahang.id.vn/member");
        assertThat(link.scope()).isEqualTo(MemberLinkService.INTERNET);
    }

    @Test void readsTheHostnameCloudflaredReports() {
        assertThat(MemberLinkService.hostnameIn("{\"hostname\":\"calm-river-1234.trycloudflare.com\"}")).contains("calm-river-1234.trycloudflare.com");
        assertThat(MemberLinkService.hostnameIn("{\"hostname\":\"\"}")).isEmpty();
        assertThat(MemberLinkService.hostnameIn("{\"hostname\":\"evil.example.com\"}")).as("only quick-tunnel names").isEmpty();
        assertThat(MemberLinkService.hostnameIn(null)).isEmpty();
    }
}
