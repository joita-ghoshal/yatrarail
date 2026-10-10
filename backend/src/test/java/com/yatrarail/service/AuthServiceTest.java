package com.yatrarail.service;

import com.yatrarail.config.SecurityProperties;
import com.yatrarail.dto.auth.AuthResponse;
import com.yatrarail.dto.auth.RegisterRequest;
import com.yatrarail.entity.RoleName;
import com.yatrarail.entity.User;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.repository.UserRepository;
import com.yatrarail.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtService jwtService;
    @Mock
    private SecurityProperties securityProperties;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, authenticationManager, jwtService,
                securityProperties);
    }

    @Test
    void registerNormalizesEmailToTrimmedLowercase() {
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(jwtService.generateToken(any())).thenReturn("jwt-token");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        RegisterRequest request = new RegisterRequest();
        request.setFullName("  Jane Doe  ");
        request.setEmail("  Jane.Doe@Example.COM ");
        request.setPhone("9876543210");
        request.setPassword("secret1");

        AuthResponse response = authService.register(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("jane.doe@example.com");
        assertThat(response.getEmail()).isEqualTo("jane.doe@example.com");
        assertThat(response.getRole()).isEqualTo(RoleName.PASSENGER.name());
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        RegisterRequest request = new RegisterRequest();
        request.setFullName("Dup User");
        request.setEmail("DUP@example.com");
        request.setPhone("9876543210");
        request.setPassword("secret1");

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
    }
}