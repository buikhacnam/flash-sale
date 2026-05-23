package com.example.flash_sale.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flashSaleOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Flash Sale API")
                .description("Modular monolith demo: Spring Boot + PostgreSQL + Redis flash-sale reservations")
                .version("0.0.1"));
    }
}
