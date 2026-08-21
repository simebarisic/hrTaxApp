package hr.porezno.taxapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Centralna CORS konfiguracija za API.
 *
 * Zasto ovo postoji: iako frontend (Nginx, docker-compose) i backend
 * izgledaju kao "isti origin" iz perspektive korisnika (Nginx proxya
 * /api/* na backend), preglednik SVEJEDNO salje Origin header na
 * POST/PUT/DELETE zahtjeve (pa i na "prividno" isti origin), i Spring
 * ga provjerava neovisno o tome sto stoji iza reverse proxyja. Zato
 * dopustena podrijetla moraju eksplicitno ukljucivati i port na kojem
 * frontend stvarno radi (npr. 5173 za Vite dev server, 8081 za Docker
 * Nginx kontejner).
 *
 * Lista se moze prilagoditi bez rekompajliranja preko env varijable
 * APP_CORS_ALLOWED_ORIGINS (comma-separated), npr. za produkcijsku
 * domenu.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:8081}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
