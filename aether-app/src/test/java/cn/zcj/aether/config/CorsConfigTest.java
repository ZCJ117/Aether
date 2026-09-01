package cn.zcj.aether.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {

    @Test
    void emptyOriginsDeniesAllCrossOrigin() {
        CorsConfiguration cfg = new CorsConfig("").buildConfiguration();
        assertNull(cfg.getAllowedOrigins());
        assertNull(cfg.getAllowedOriginPatterns());
        assertFalse(cfg.getAllowCredentials());
    }

    @Test
    void wildcardOriginsDisablesCredentials() {
        CorsConfiguration cfg = new CorsConfig("*").buildConfiguration();
        assertNotNull(cfg.getAllowedOriginPatterns());
        assertFalse(cfg.getAllowCredentials());
    }

    @Test
    void specificOriginsEnablesCredentials() {
        CorsConfiguration cfg = new CorsConfig("https://a.example.com,https://b.example.com").buildConfiguration();
        assertEquals(2, cfg.getAllowedOrigins().size());
        assertTrue(cfg.getAllowCredentials());
    }
}
