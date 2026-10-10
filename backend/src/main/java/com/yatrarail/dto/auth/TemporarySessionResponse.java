package com.yatrarail.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Opening a guest session: the short-lived token plus the session facts the UI renders. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemporarySessionResponse {

    private String token;
    private TemporarySessionDto session;
}
