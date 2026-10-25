package com.byy.ticket.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.byy.ticket.auth.config.AuthProperties;
import com.byy.ticket.auth.dto.*;
import com.byy.ticket.auth.exception.AuthException;
import com.byy.ticket.auth.mapper.UserMapper;
import com.byy.ticket.auth.model.TicketUser;
import com.byy.ticket.auth.service.impl.AuthServiceImpl;
import com.byy.ticket.auth.token.*;

import org.junit.jupiter.api.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.nio.file.Path;
import java.time.Duration;

class AuthServiceTest {
    UserMapper users = mock(UserMapper.class);
    PasswordEncoder passwords = mock(PasswordEncoder.class);
    TokenService tokens = mock(TokenService.class);
    RedisSessionService sessions = mock(RedisSessionService.class);
    AuthServiceImpl service;

    @BeforeEach
    void setup() {
        when(passwords.encode(anyString())).thenReturn("hash");
        service =
                new AuthServiceImpl(
                        users,
                        passwords,
                        tokens,
                        sessions,
                        mock(JwtDecoder.class),
                        new AuthProperties(
                                "test",
                                "test",
                                Duration.ofMinutes(1),
                                Duration.ofHours(1),
                                Path.of("unused"),
                                Path.of("unused"),
                                "test:"));
    }

    @Test
    void publicRegistrationCannotChooseAdminAndStoresOnlyHash() {
        service.register(new RegisterDTO("BUYER", "valid-password", " User "));
        var captured = org.mockito.ArgumentCaptor.forClass(TicketUser.class);
        verify(users).insert(captured.capture());
        assertEquals("USER", captured.getValue().getRole());
        assertEquals("buyer", captured.getValue().getUsername());
        assertEquals("hash", captured.getValue().getPasswordHash());
    }

    @Test
    void unknownAccountAndWrongPasswordNeverIssueTokens() {
        assertThrows(AuthException.class, () -> service.login(new LoginDTO("missing", "wrong")));
        var user = new TicketUser();
        user.setStatus("ACTIVE");
        user.setPasswordHash("hash");
        when(users.findByUsername("buyer")).thenReturn(user);
        assertThrows(AuthException.class, () -> service.login(new LoginDTO("buyer", "wrong")));
        verifyNoInteractions(tokens, sessions);
        verify(passwords, times(2)).matches(eq("wrong"), anyString());
    }

    @Test
    void duplicateAndOverlongPasswordFailBeforeSessionCreation() {
        when(users.insert(any(TicketUser.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("test"));
        assertThrows(
                AuthException.class,
                () -> service.register(new RegisterDTO("buyer", "valid-password", "User")));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.login(new LoginDTO("buyer", "中".repeat(25))));
        verifyNoInteractions(tokens, sessions);
    }
}
