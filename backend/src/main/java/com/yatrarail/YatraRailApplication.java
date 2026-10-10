package com.yatrarail;

import com.yatrarail.config.BookingProperties;
import com.yatrarail.config.CacheProperties;
import com.yatrarail.config.PlatformProperties;
import com.yatrarail.config.RefundProperties;
import com.yatrarail.config.SecurityProperties;
import com.yatrarail.config.TemporarySessionProperties;
import com.yatrarail.config.TicketProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * YatraRail - Indian Railway inspired Ticket Booking & Management System.
 * Academic / portfolio project. Not affiliated with or endorsed by Indian Railways or IRCTC.
 */
@SpringBootApplication
@EnableConfigurationProperties({SecurityProperties.class, BookingProperties.class,
        TemporarySessionProperties.class, RefundProperties.class, TicketProperties.class,
        CacheProperties.class, PlatformProperties.class})
@EnableScheduling
public class YatraRailApplication {
    public static void main(String[] args) {
        SpringApplication.run(YatraRailApplication.class, args);
    }
}
