package com.yatrarail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yatrarail.dto.auth.RegisterRequest;
import com.yatrarail.entity.NotificationType;
import com.yatrarail.entity.RoleName;
import com.yatrarail.entity.User;
import com.yatrarail.repository.UserRepository;
import com.yatrarail.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private NotificationService notificationService;

    private String userAToken;
    private String alphaEmail;
    private String userBToken;

    @BeforeEach
    void setUp() throws Exception {
        int n = COUNTER.incrementAndGet();
        alphaEmail = "alpha" + n + "@example.com";
        userAToken = registerUser(alphaEmail, "Alpha One");
        userBToken = registerUser("bravo" + n + "@example.com", "Bravo Two");
    }

    private String registerUser(String email, String fullName) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setFullName(fullName);
        request.setEmail(email);
        request.setPhone("987" + String.format("%07d", System.nanoTime() % 10000000L));
        request.setPassword("Password@123");

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").exists())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("data").path("token").asText();
    }

    @Test
    void meReturnsProfileWithoutPasswordField() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(alphaEmail))
                .andExpect(jsonPath("$.data.fullName").value("Alpha One"))
                .andExpect(jsonPath("$.data.role").value("PASSENGER"))
                .andExpect(jsonPath("$.data.phone").exists())
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void meRejectsAnonymousRequestWithJson401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void protectedEndpointRejectsAnonymousWithJson401() throws Exception {
        mockMvc.perform(get("/api/bookings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void passengerCannotAccessAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/trains").header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void seededAdminCanAccessAdminEndpoints() throws Exception {
        String adminToken = loginToken("admin@yatrarail.demo", "Admin@123");

        mockMvc.perform(get("/api/admin/trains").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(7));
    }

    @Test
    void disabledAccountCannotLogin() throws Exception {
        User alpha = userRepository.findByEmail(alphaEmail).orElseThrow();
        alpha.setActive(false);
        userRepository.save(alpha);

        Map<String, String> body = new HashMap<>();
        body.put("email", alphaEmail);
        body.put("password", "Password@123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Account is disabled"));
    }

    @Test
    void userCannotMarkAnotherUsersNotificationAsRead() throws Exception {
        Long alphaUserId = userRepository.findByEmail(alphaEmail).orElseThrow().getId();
        User alpha = userRepository.findById(alphaUserId).orElseThrow();
        alpha.setGender("Female");
        alpha.setAge(28);
        userRepository.save(alpha);

        notificationService.notify(alpha, NotificationType.CANCELLATION, "Private note", "Only alpha should see this.");

        Long noteId = notificationService.getForUser(alphaUserId).stream()
                .filter(n -> "Private note".equals(n.getTitle()))
                .findFirst()
                .orElseThrow()
                .getId();

        // User B must NOT be able to mark A's private notification as read (IDOR guard).
        mockMvc.perform(patch("/api/notifications/{id}/read", noteId)
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isNotFound());

        // Alpha can mark it read.
        mockMvc.perform(patch("/api/notifications/{id}/read", noteId)
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk());
    }

    private String loginToken(String email, String password) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("data").path("token").asText()).isNotBlank();
        return json.path("data").path("token").asText();
    }
}