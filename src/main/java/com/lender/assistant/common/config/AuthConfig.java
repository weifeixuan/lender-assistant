package com.lender.assistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 口令编码器：只引 spring-security-crypto 的 BCrypt，不引入 Spring Security 全家桶
 * （取舍见 ADR-0019：全家桶的默认值会改掉现有接口的对外行为）。
 */
@Configuration
public class AuthConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
