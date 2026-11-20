package kyle.poultry.shared;

import org.springframework.context.annotation.Configuration;
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
}
