package io.github.brick.web.api;

import io.github.brick.contract.game.*;
import io.github.brick.web.auth.*;
import io.github.brick.web.domain.account.Account;
import io.github.brick.web.domain.player.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessApiTest {
    final AuthService auth = mock(AuthService.class);
    final SessionStore sessions = mock(SessionStore.class);
    final PlayerService players = mock(PlayerService.class);
    final LoginRateLimiter limiter = mock(LoginRateLimiter.class);
    final SessionIdentity identity = new SessionIdentity(1, 11, Long.MAX_VALUE, "digest");
    final PlayerProfile profile = new PlayerProfile(11, 1, "Alice", 100, 100);
    MockMvc mvc;

    @BeforeEach
    void setup() {
        var errors = new ApiErrorWriter();
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth, sessions, limiter), new PlayerController(players))
                .setMessageConverters(new ProtobufHttpMessageConverter()).setControllerAdvice(new ApiExceptionHandler(errors))
                .addFilters(new RequestBodyLimitFilter(errors), new AuthFilter(sessions, limiter)).build();
        when(sessions.authenticate("valid")).thenReturn(identity);
    }

    @Test
    void successfulResponsesUseRealProtobufAndCleanupIdentity() throws Exception {
        when(auth.register("alice", "long password 123")).thenReturn(new Account(1, "alice", "hidden", 11, false, 100));
        var registration = RegisterRequest.newBuilder().setUsername("alice").setPassword("long password 123").build();
        var r = mvc.perform(post("/api/auth/register").contentType("application/x-protobuf").content(registration.toByteArray())).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(RegisterResponse.parseFrom(r.getContentAsByteArray()).getAccountId()).isEqualTo(1);
        when(players.get(identity)).thenReturn(profile);
        r = mvc.perform(post("/api/player/profile").header("Authorization", "Bearer valid").contentType("application/x-protobuf")).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(ProfileResponse.parseFrom(r.getContentAsByteArray()).getProfile().getUid()).isEqualTo(11);
        assertThatThrownBy(RequestIdentity::require).isInstanceOf(GameException.class);
        assertThat(org.slf4j.MDC.get("userId")).isNull();
    }

    @Test
    void malformedUnauthorizedAndBackendFailuresAreProtobufErrors() throws Exception {
        var r = mvc.perform(post("/api/player/profile").contentType("application/x-protobuf")).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(ErrorResponse.parseFrom(r.getContentAsByteArray()).getRequestId()).isNotBlank();
        r = mvc.perform(post("/api/auth/login").contentType("application/x-protobuf").content(new byte[]{(byte) 0x80})).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(400);
        r = mvc.perform(post("/api/auth/login").contentType("text/plain").content("secret")).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(415);
        r = mvc.perform(get("/api/auth/login")).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(405);
        for (int status : new int[]{403, 409, 429, 503, 500}) {
            doThrow(new GameException(status, "TEST", "safe")).when(players).get(identity);
            r = mvc.perform(post("/api/player/profile").header("Authorization", "Bearer valid").contentType("application/x-protobuf")).andReturn().getResponse();
            assertThat(r.getStatus()).isEqualTo(status);
            assertThat(ErrorResponse.parseFrom(r.getContentAsByteArray()).getCode()).isEqualTo("TEST");
            assertThatThrownBy(RequestIdentity::require).isInstanceOf(GameException.class);
        }
    }

    @Test
    void loginRenameAndLogoutExposeOnlyOwnedProfile() throws Exception {
        when(auth.login(eq("alice"), eq("long password 123"), anyString())).thenReturn(new AuthService.LoginResult(new SessionStore.IssuedSession("issued", identity), profile));
        var login = LoginRequest.newBuilder().setUsername("alice").setPassword("long password 123").build();
        var r = mvc.perform(post("/api/auth/login").contentType("application/x-protobuf").content(login.toByteArray())).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(LoginResponse.parseFrom(r.getContentAsByteArray()).getToken()).isEqualTo("issued");
        when(players.rename(eq(identity), eq("New"), anyString())).thenReturn(new PlayerProfile(11, 1, "New", 100, 200));
        r = mvc.perform(post("/api/player/rename").header("Authorization", "Bearer valid").contentType("application/x-protobuf").content(RenameRequest.newBuilder().setDisplayName("New").build().toByteArray())).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(ProfileResponse.parseFrom(r.getContentAsByteArray()).getProfile().getDisplayName()).isEqualTo("New");
        r = mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer valid").contentType("application/x-protobuf")).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(LogoutResponse.parseFrom(r.getContentAsByteArray()).getOk()).isTrue();
        verify(sessions).revoke(identity);
    }

    @Test
    void normalizedApiAliasesCannotBypassRequestBoundaries() throws Exception {
        when(auth.register("alice", "long password 123")).thenReturn(new Account(1, "alice", "hidden", 11, false, 100));
        var body = RegisterRequest.newBuilder().setUsername("alice").setPassword("long password 123").build().toByteArray();
        for (String path : new String[]{"/api;x/auth/register", "/%61pi/auth/register"}) {
            for (boolean authenticated : new boolean[]{false, true}) {
                var request = post(java.net.URI.create(path)).contentType("application/x-protobuf").content(body);
                if (authenticated) request.header("Authorization", "Bearer valid");
                var response = mvc.perform(request).andReturn().getResponse();
                assertThat(response.getStatus()).as(path).isEqualTo(400);
                assertThat(ErrorResponse.parseFrom(response.getContentAsByteArray()).getRequestId()).isNotBlank();
            }
        }
        verifyNoInteractions(auth);
    }
}
