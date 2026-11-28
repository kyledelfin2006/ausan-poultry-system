package kyle.poultry.shared.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;

/**
 * Makes every paged endpoint answer with a stable JSON shape.
 * <p>
 * Serialising a Spring Data {@code Page} directly exposes framework internals ({@code pageable}, {@code sort},
 * {@code offset}, ...) that Spring Data itself marks as unstable. With {@link PageSerializationMode#VIA_DTO} the body is
 * {@code { "content": [...], "page": { "size", "number", "totalElements", "totalPages" } }}, which is what the API
 * contract in {@code docs/api} promises the frontend. Package-private; Spring finds it by component scanning.
 */
@Configuration(proxyBeanMethods = false)
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
class PageSerializationConfig {

    /** Largest page any endpoint will serve; a larger {@code size} request is silently reduced to this. */
    static final int MAX_PAGE_SIZE = 100;

    /**
     * Caps {@code size} for every paged endpoint. It is set here, not with {@code spring.data.web.pageable.*}, because
     * {@link EnableSpringDataWebSupport} registers its own resolver, which makes Spring Boot's property-driven
     * auto-configuration back off and silently ignore that property.
     *
     * @return the customizer applied to the pageable argument resolver
     */
    @Bean
    PageableHandlerMethodArgumentResolverCustomizer maxPageSizeCustomizer() {
        return resolver -> resolver.setMaxPageSize(MAX_PAGE_SIZE);
    }
}
