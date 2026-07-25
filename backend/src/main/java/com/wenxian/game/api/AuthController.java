package com.wenxian.game.api;

import com.wenxian.game.auth.AuthService;
import com.wenxian.game.auth.AuthService.LoginOutcome;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.persistence.SaveStore;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final SaveStore saveStore;
    private final boolean secureCookie;

    public AuthController(AuthService authService, SaveStore saveStore,
                          @Value("${game.auth.secure-cookie:false}") boolean secureCookie) {
        this.authService = authService;
        this.saveStore = saveStore;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        LoginOutcome outcome = authService.login(request.email(), request.inviteCode());
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(outcome.rawToken(), authService.sessionDays()).toString());
        boolean hasSave = saveStore.findCurrentForUser(outcome.user().userId()) != null;
        return response(outcome.user(), outcome.newUser(), hasSave);
    }

    @GetMapping("/me")
    public AuthResponse me(@CookieValue(name = AuthService.COOKIE_NAME, required = false) String token) {
        UserIdentity user = authService.currentUser(token);
        if (user == null) return new AuthResponse(false, false, null, false, "LOGIN");
        boolean hasSave = saveStore.findCurrentForUser(user.userId()) != null;
        return response(user, false, hasSave);
    }

    @PostMapping("/logout")
    public void logout(@CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
                       HttpServletResponse response) {
        authService.logout(token);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString());
    }

    private AuthResponse response(UserIdentity user, boolean newUser, boolean hasSave) {
        return new AuthResponse(true, newUser, new UserView(user.userId(), maskEmail(user.email())), hasSave,
                hasSave ? "RESUME_GAME" : "CREATE_CHARACTER");
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

    public record LoginRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @Size(max = 64) String inviteCode
    ) {}

    public record UserView(String userId, String email) {}
    public record AuthResponse(boolean authenticated, boolean newUser, UserView user, boolean hasSave, String nextStep) {}
}
