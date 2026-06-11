package com.worknote;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WorknoteApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorknoteApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
