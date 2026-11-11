package de.hsh.favs.sharewise.shared.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Core application configuration properties bound at startup.
 *
 * <p>Validates required settings eagerly during record construction so invalid configuration
 * fails application startup deterministically.
 *
 * @param name the non-blank application name
 * @param apiDefaultPageSize default page size for paginated read endpoints (1..200)
 */
@ConfigurationProperties(prefix = "sharewise.app")
public record ShareWiseProperties(String name, int apiDefaultPageSize) {

    private static final String DEFAULT_NAME = "sharewise";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    public ShareWiseProperties {
        String resolvedName = (name == null) ? DEFAULT_NAME : name;
        if (resolvedName.isBlank()) {
            throw new IllegalArgumentException("sharewise.app.name must not be blank");
        }
        int resolvedPageSize = (apiDefaultPageSize == 0) ? DEFAULT_PAGE_SIZE : apiDefaultPageSize;
        if (resolvedPageSize < 1 || resolvedPageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "sharewise.app.api-default-page-size must be between 1 and " + MAX_PAGE_SIZE);
        }
        name = resolvedName;
        apiDefaultPageSize = resolvedPageSize;
    }
}
