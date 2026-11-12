package com.example.PhanTichAI.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /**
     * Customers' member card (/member). Open to everyone; MemberController checks the member session itself, which
     * only holds a customer id and never a staff role, so a customer can never reach the POS or the admin pages.
     * CSRF protection stays on for every change (register, sign in, activate, sign out).
     */
    @Bean @Order(1) SecurityFilterChain memberSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/member", "/member/**", "/api/member/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors.accessDeniedHandler((req, res, ex) ->
                    json(res, 403, "Phiên làm việc đã hết hạn. Hãy tải lại trang rồi thử lại.")));
        return http.build();
    }

    @Bean @Order(2) SecurityFilterChain security(HttpSecurity http, AccountService accounts) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/setup", "/error", "/css/**", "/js/**", "/images/**", "/favicon.svg").permitAll()
                .requestMatchers("/api/admin/users/**").hasRole("ADMIN")
                .requestMatchers("/admin", "/api/admin/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers("/", "/pos", "/api/pos/**", "/api/auth/me").hasAnyRole("ADMIN", "MANAGER", "CASHIER")
                .anyRequest().denyAll())
            .formLogin(form -> form.loginPage("/login").successHandler((req, res, auth) -> {
                var user = (StoreUser) auth.getPrincipal();
                res.sendRedirect("CASHIER".equals(user.getRole()) ? "/pos" : "/admin");
            }).permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/login?logout").invalidateHttpSession(true).deleteCookies("JSESSIONID"))
            .exceptionHandling(errors -> errors
                .defaultAuthenticationEntryPointFor((req, res, ex) -> json(res, 401, "Phiên đăng nhập đã hết hạn."), new AntPathRequestMatcher("/api/**"))
                .accessDeniedHandler((req, res, ex) -> {
                    if (req.getRequestURI().startsWith("/api/")) json(res, 403, "Bạn không có quyền thực hiện thao tác hoặc mã bảo vệ đã hết hạn.");
                    else res.sendError(403, "Bạn không có quyền truy cập trang này.");
                }))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
                    var auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth != null && auth.getPrincipal() instanceof StoreUser previous) {
                        try {
                            var current = accounts.loadUserByUsername(previous.getUsername());
                            if (!current.isEnabled() || !current.getPasswordVersion().equals(previous.getPasswordVersion())) throw new IllegalStateException();
                            current.eraseCredentials();
                            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(current, auth.getCredentials(), current.getAuthorities()));
                        } catch (RuntimeException ex) {
                            SecurityContextHolder.clearContext();
                            if (req.getSession(false) != null) req.getSession(false).invalidate();
                        }
                    }
                    chain.doFilter(req, res);
                }
            }, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    private static void json(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
    }
}
