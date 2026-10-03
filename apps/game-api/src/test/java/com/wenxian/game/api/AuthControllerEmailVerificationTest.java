package com.wenxian.game.api;

import com.wenxian.game.analytics.AnalyticsEventPublisher;
import com.wenxian.game.auth.AccessPolicyService;
import com.wenxian.game.auth.AuthService;
import com.wenxian.game.auth.RegistrationEmailVerificationService;
import com.wenxian.game.story.persistence.StorySaveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerEmailVerificationTest {
    private AuthService auth;
    private StorySaveRepository saves;
    private RegistrationEmailVerificationService verification;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        auth = mock(AuthService.class);
        saves = mock(StorySaveRepository.class);
        verification = mock(RegistrationEmailVerificationService.class);
        AuthController controller = new AuthController(
                auth,
                saves,
                mock(AccessPolicyService.class),
                mock(AnalyticsEventPublisher.class),
                verification,
                false
        );
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsOnlyChallengeTimingAfterRequestingCode() throws Exception {
        when(verification.requestRegistrationCode("player@example.com", "127.0.0.1"))
                .thenReturn(new RegistrationEmailVerificationService.RequestOutcome(
                        "verification-123", 600, 60));

        mvc.perform(post("/api/v1/auth/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"player@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationId").value("verification-123"))
                .andExpect(jsonPath("$.expiresInSeconds").value(600))
                .andExpect(jsonPath("$.resendAfterSeconds").value(60))
                .andExpect(jsonPath("$.verificationCode").doesNotExist());

        verify(verification).requestRegistrationCode("player@example.com", "127.0.0.1");
    }

    @Test
    void forwardsChallengeAndSixDigitCodeIntoRegistration() throws Exception {
        AuthService.LoginOutcome outcome = new AuthService.LoginOutcome(
                new AuthService.UserIdentity("user-1", "player@example.com", true),
                true,
                "raw-session-token",
                Instant.now().plusSeconds(3600)
        );
        when(auth.register("player@example.com", "password123", "verification-123", "123456",
                "127.0.0.1")).thenReturn(outcome);
        when(auth.sessionDays()).thenReturn(30);
        when(saves.hasActive(anyString())).thenReturn(false);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"player@example.com",
                                  "password":"password123",
                                  "verificationId":"verification-123",
                                  "verificationCode":"123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.newUser").value(true));

        verify(auth).register("player@example.com", "password123", "verification-123", "123456",
                "127.0.0.1");
    }
}
