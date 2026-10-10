package com.yatrarail.config;

import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Hibernate module so lazily-loaded JPA associations serialize cleanly to JSON
 * (as null/omitted when not initialized, instead of leaking Hibernate proxy internals).
 * NOTE: FORCE_LAZY_LOADING is intentionally NOT enabled — with open-in-view=false it would
 * cause LazyInitializationException; read endpoints must use DTOs instead of raw entities.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Hibernate6Module hibernate6Module() {
        return new Hibernate6Module();
    }
}
