package com.example.PhanTichAI.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * The door to the Internet for customers' phones on any network (4G, another Wi-Fi).
 *
 * <p>A second port ({@code member.public-port}, 8081) listens on 127.0.0.1 only, so nothing but this computer can reach it —
 * in practice the Cloudflare tunnel ({@code scripts/public-member.cmd}) that forwards https://….trycloudflare.com here.
 * On that port only the member card works: the POS, the admin pages, staff sign-in and every other address answer
 * "not found", so the demo staff accounts are never exposed. The normal port 8080 is unchanged.</p>
 *
 * <p>Because only the tunnel can connect to the public port, its {@code CF-Connecting-IP} header (the phone's real address)
 * can be trusted there; it is used to limit sign-up / sign-in attempts per phone ({@link MemberRateLimiter}).</p>
 */
@Configuration
public class PublicMemberGateway {
    public static final String PUBLIC_ATTRIBUTE = PublicMemberGateway.class.getName() + ".PUBLIC";
    public static final String CLIENT_ADDRESS_ATTRIBUTE = PublicMemberGateway.class.getName() + ".CLIENT";

    /** What a phone may open through the tunnel: the member page, its API and the files that page loads. */
    private static final Set<String> MEMBER_FILES = Set.of("/css/member.css", "/js/member.js", "/js/qr-svg.js",
            "/js/vendor/qrcode-generator-1.4.4.js", "/favicon.svg");

    static boolean allowedFromInternet(String path) {
        return path.equals("/member") || path.startsWith("/member/") || path.startsWith("/api/member/") || MEMBER_FILES.contains(path);
    }

    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> publicMemberPort(@Value("${member.public-port:8081}") int port) {
        return factory -> {
            if (port <= 0) return;                          // switched off (tests, or a store that does not want it)
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(port);
            connector.setProperty("address", "127.0.0.1");  // never reachable from the Wi-Fi or the Internet directly
            factory.addAdditionalTomcatConnectors(connector);
        };
    }

    @Bean FilterRegistrationBean<Filter> publicMemberFilter(@Value("${member.public-port:8081}") int port) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(new Filter(port));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);   // before Spring Security
        registration.addUrlPatterns("/*");
        return registration;
    }

    /** Address of the customer's device: the tunnel's header on the public port, the socket address otherwise. */
    public static String clientAddress(HttpServletRequest request) {
        Object known = request.getAttribute(CLIENT_ADDRESS_ATTRIBUTE);
        return known instanceof String address ? address : request.getRemoteAddr();
    }

    public static final class Filter extends OncePerRequestFilter {
        private final int publicPort;

        public Filter(int publicPort) { this.publicPort = publicPort; }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            boolean fromTunnel = publicPort > 0 && request.getLocalPort() == publicPort;
            if (fromTunnel) {
                String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
                if (path.isEmpty() || path.equals("/")) {
                    // relative Location: the phone stays on https (sendRedirect would build an http:// address)
                    response.setStatus(HttpServletResponse.SC_FOUND);
                    response.setHeader("Location", "/member");
                    return;
                }
                if (!allowedFromInternet(path)) {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    response.setContentType("text/plain;charset=UTF-8");
                    response.getWriter().write("Không tìm thấy trang.");
                    return;
                }
                String phone = request.getHeader("CF-Connecting-IP");
                request.setAttribute(PUBLIC_ATTRIBUTE, Boolean.TRUE);
                request.setAttribute(CLIENT_ADDRESS_ATTRIBUTE, phone == null || phone.isBlank() ? request.getRemoteAddr() : phone.trim());
            }
            chain.doFilter(request, response);
        }
    }
}
