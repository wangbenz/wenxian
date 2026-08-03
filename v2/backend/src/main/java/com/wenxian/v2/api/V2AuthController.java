package com.wenxian.v2.api;

import com.wenxian.v2.account.SharedAccountSessionService;
import com.wenxian.v2.account.V2AccessPolicyService;
import com.wenxian.v2.account.V2AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v2/auth")
public class V2AuthController {
    private final V2AccountService accounts;
    private final V2AccessPolicyService access;
    private final boolean secureCookie;

    public V2AuthController(V2AccountService accounts, V2AccessPolicyService access,
                            @org.springframework.beans.factory.annotation.Value("${wenxian.v2.auth.secure-cookie:false}")
                            boolean secureCookie) {
        this.accounts = accounts;
        this.access = access;
        this.secureCookie = secureCookie;
    }

    @GetMapping("/access-status")
    public V2AccessPolicyService.AccessStatus accessStatus() {
        return access.status();
    }

    @GetMapping("/me")
    public AuthResponse me(@CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token) {
        SharedAccountSessionService.Account account = accounts.current(token);
        return account == null ? new AuthResponse(false, false, null, false, "LOGIN")
                : response(account, false);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse servletResponse) {
        V2AccountService.LoginOutcome outcome = accounts.login(request.email(), request.password());
        servletResponse.addHeader(HttpHeaders.SET_COOKIE,
                cookie(outcome.rawToken(), outcome.sessionDays()).toString());
        return response(outcome.account(), outcome.newUser());
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest,
                                 HttpServletResponse servletResponse) {
        V2AccountService.LoginOutcome outcome = accounts.register(request.email(), request.password(),
                clientIp(servletRequest));
        servletResponse.addHeader(HttpHeaders.SET_COOKIE,
                cookie(outcome.rawToken(), outcome.sessionDays()).toString());
        return response(outcome.account(), outcome.newUser());
    }

    @PutMapping("/password")
    public AuthResponse changePassword(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody PasswordRequest request) {
        return response(accounts.changePassword(token, request.currentPassword(), request.newPassword()), false);
    }

    @PostMapping("/logout")
    public void logout(@CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
                       HttpServletResponse response) {
        accounts.logout(token);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
    }

    private AuthResponse response(SharedAccountSessionService.Account account, boolean newUser) {
        return new AuthResponse(true, newUser, new UserView(account.userId(), mask(account.email())),
                account.passwordConfigured(), account.passwordConfigured() ? "GAME" : "CHANGE_PASSWORD");
    }

    private ResponseCookie cookie(String token, int days) {
        return ResponseCookie.from(SharedAccountSessionService.COOKIE_NAME, token)
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/")
                .maxAge(Duration.ofDays(days)).build();
    }

    private String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(0, at))
                : email.charAt(0) + "***" + email.substring(at - 1);
    }

    private String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (("127.0.0.1".equals(remote) || "::1".equals(remote)) && forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].strip();
        }
        return remote;
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 255) String email,
                               @NotBlank @Size(max = 255) String password) {
    }

    public record RegisterRequest(@NotBlank @Email @Size(max = 255) String email,
                                  @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record PasswordRequest(@Size(max = 72) String currentPassword,
                                  @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    public record UserView(String userId, String email) {
    }

    public record AuthResponse(boolean authenticated, boolean newUser, UserView user,
                               boolean passwordConfigured, String nextStep) {
    }
}
