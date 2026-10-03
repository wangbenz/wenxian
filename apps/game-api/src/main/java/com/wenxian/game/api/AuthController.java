package com.wenxian.game.api;

import com.wenxian.game.analytics.AnalyticsEventPublisher;
import com.wenxian.game.analytics.AnalyticsEventType;
import com.wenxian.game.auth.AuthService;
import com.wenxian.game.auth.AuthService.LoginOutcome;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.auth.AccessPolicyService;
import com.wenxian.game.auth.RegistrationEmailVerificationService;
import com.wenxian.game.story.persistence.StorySaveRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final StorySaveRepository saves;
    private final boolean secureCookie;
    private final AccessPolicyService accessPolicy;
    private final AnalyticsEventPublisher analytics;
    private final RegistrationEmailVerificationService emailVerification;

    public AuthController(AuthService authService, StorySaveRepository saves, AccessPolicyService accessPolicy,
                          AnalyticsEventPublisher analytics, RegistrationEmailVerificationService emailVerification,
                          @Value("${game.auth.secure-cookie:false}") boolean secureCookie) {
        this.authService = authService;
        this.saves = saves;
        this.secureCookie = secureCookie;
        this.accessPolicy = accessPolicy;
        this.analytics = analytics;
        this.emailVerification = emailVerification;
    }

    @GetMapping("/access-status")
    public AccessPolicyService.AccessStatus accessStatus() {
        return accessPolicy.status();
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
                              HttpServletResponse response) {
        LoginOutcome outcome = authService.login(request.email(), request.password(), clientIp(httpRequest));
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(outcome.rawToken(), authService.sessionDays()).toString());
        boolean hasSave = hasSave(outcome.user().userId());
        return response(outcome.user(), outcome.newUser(), hasSave);
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest,
                                 HttpServletResponse response) {
        LoginOutcome outcome = authService.register(request.email(), request.password(), request.verificationId(),
                request.verificationCode(), clientIp(httpRequest));
        if (outcome.newUser()) {
            analytics.publish("USER_REGISTERED:" + outcome.user().userId(), AnalyticsEventType.USER_REGISTERED,
                    outcome.user().userId(), null, "", Map.of());
        }
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(outcome.rawToken(), authService.sessionDays()).toString());
        boolean hasSave = hasSave(outcome.user().userId());
        return response(outcome.user(), outcome.newUser(), hasSave);
    }

    @PostMapping("/email-verifications")
    public EmailVerificationResponse requestEmailVerification(
            @Valid @RequestBody EmailVerificationRequest request,
            HttpServletRequest httpRequest) {
        RegistrationEmailVerificationService.RequestOutcome outcome = emailVerification.requestRegistrationCode(
                request.email(), clientIp(httpRequest));
        return new EmailVerificationResponse(
                outcome.verificationId(), outcome.expiresInSeconds(), outcome.resendAfterSeconds());
    }

    @PostMapping("/password")
    public AuthResponse setPassword(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody SetPasswordRequest request) {
        UserIdentity user = authService.setPassword(token, request.currentPassword(), request.newPassword());
        boolean hasSave = hasSave(user.userId());
        return response(user, false, hasSave);
    }

    @GetMapping("/me")
    public AuthResponse me(@CookieValue(name = AuthService.COOKIE_NAME, required = false) String token) {
        UserIdentity user = authService.currentUser(token);
        if (user == null) return new AuthResponse(false, false, null, false, false, "LOGIN");
        boolean hasSave = hasSave(user.userId());
        return response(user, false, hasSave);
    }

    @PostMapping("/logout")
    public void logout(@CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
                       HttpServletResponse response) {
        authService.logout(token);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString());
    }

    @DeleteMapping("/account")
    public void deleteAccount(@CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
                              @Valid @RequestBody DeleteAccountRequest request,
                              HttpServletResponse response) {
        if (!"确认注销".equals(request.confirmation())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "请输入“确认注销”后再继续");
        }
        authService.deleteAccount(token);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString());
    }

    private AuthResponse response(UserIdentity user, boolean newUser, boolean hasSave) {
        return new AuthResponse(true, newUser, new UserView(user.userId(), maskEmail(user.email())), hasSave,
                user.passwordConfigured(),
                hasSave ? "RESUME_GAME" : "CREATE_CHARACTER");
    }

    private boolean hasSave(String userId) {
        return saves.hasActive(userId);
    }

    private ResponseCookie sessionCookie(String token, int days) {
        return ResponseCookie.from(AuthService.COOKIE_NAME, token)
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/")
                .maxAge(Duration.ofDays(days)).build();
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(0, at));
        return email.charAt(0) + "***" + email.substring(at - 1);
    }

    private String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        boolean trustedProxy = "127.0.0.1".equals(remote) || "::1".equals(remote)
                || "0:0:0:0:0:0:0:1".equals(remote);
        String forwarded = request.getHeader("X-Forwarded-For");
        if (trustedProxy && forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].strip();
        }
        return remote;
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 1, max = 255) String password
    ) {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @Size(max = 64) String verificationId,
            @Pattern(regexp = "\\d{6}", message = "必须是六位数字") String verificationCode
    ) {}

    public record EmailVerificationRequest(
            @NotBlank @Email @Size(max = 255) String email
    ) {}

    public record EmailVerificationResponse(
            String verificationId,
            long expiresInSeconds,
            long resendAfterSeconds
    ) {}

    public record SetPasswordRequest(
            @Size(max = 72) String currentPassword,
            @NotBlank @Size(min = 8, max = 72) String newPassword
    ) {}

    public record DeleteAccountRequest(@NotBlank @Size(max = 16) String confirmation) {}

    public record UserView(String userId, String email) {}
    public record AuthResponse(boolean authenticated, boolean newUser, UserView user, boolean hasSave,
                               boolean passwordConfigured, String nextStep) {}
}
