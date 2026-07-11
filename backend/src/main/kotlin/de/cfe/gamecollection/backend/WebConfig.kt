package de.cfe.gamecollection.backend

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOrigins("http://localhost:1420", "tauri://localhost", "http://tauri.localhost", "https://tauri.localhost")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
    }
}
