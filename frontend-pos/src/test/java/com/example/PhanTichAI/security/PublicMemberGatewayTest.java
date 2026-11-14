package com.example.PhanTichAI.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Through the tunnel (public port) only the member card is reachable; the shop port is unchanged. */
class PublicMemberGatewayTest {
    private static final int PUBLIC = 8081, SHOP = 8080;
    private final PublicMemberGateway.Filter filter = new PublicMemberGateway.Filter(PUBLIC);

    private record Result(MockHttpServletResponse response, MockHttpServletRequest request, boolean passed) {}

    private Result call(int port, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.setLocalPort(port);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("CF-Connecting-IP", "203.0.113.7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response, request, ((MockFilterChain) chain).getRequest() != null);
    }

    @Test void phonesOnTheInternetReachTheMemberCardAndItsFiles() throws Exception {
        for (String path : new String[]{"/member", "/member/manifest.json", "/api/member/login", "/api/member/qr",
                "/css/member.css", "/js/member.js", "/js/qr-svg.js", "/js/vendor/qrcode-generator-1.4.4.js", "/favicon.svg"})
            assertThat(call(PUBLIC, path).passed()).as(path).isTrue();
    }

    @Test void staffPagesAreNotFoundFromTheInternet() throws Exception {
        for (String path : new String[]{"/pos", "/admin", "/login", "/setup", "/api/pos/products", "/api/admin/ai/model-status",
                "/api/auth/me", "/css/admin.css", "/js/pos.js", "/error", "/memberx", "/api/memberx"}) {
            Result result = call(PUBLIC, path);
            assertThat(result.passed()).as(path).isFalse();
            assertThat(result.response().getStatus()).as(path).isEqualTo(404);
        }
    }

    @Test void rootOfThePublicAddressOpensTheMemberCard() throws Exception {
        Result result = call(PUBLIC, "/");
        assertThat(result.passed()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(302);
        assertThat(result.response().getHeader("Location")).isEqualTo("/member");
    }

    @Test void shopPortIsUnchangedAndDoesNotTrustTheTunnelHeader() throws Exception {
        Result result = call(SHOP, "/pos");
        assertThat(result.passed()).isTrue();
        assertThat(PublicMemberGateway.clientAddress(result.request())).isEqualTo("127.0.0.1");
    }

    @Test void onThePublicPortTheDeviceIsTheAddressGivenByTheTunnel() throws Exception {
        Result result = call(PUBLIC, "/api/member/login");
        assertThat(PublicMemberGateway.clientAddress(result.request())).isEqualTo("203.0.113.7");
    }

    @Test void switchedOffMeansNothingIsFiltered() throws Exception {
        PublicMemberGateway.Filter off = new PublicMemberGateway.Filter(-1);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/pos");
        request.setServletPath("/pos");
        request.setLocalPort(-1);
        MockFilterChain chain = new MockFilterChain();
        off.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
