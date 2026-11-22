package de.hsh.favs.sharewise.identity.internal.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;

/** Static, non-sensitive transport failures that happen before MVC exception advice. */
final class IamHttpProblemWriter {

    private IamHttpProblemWriter() { }

    static void write(HttpServletResponse response, int status, String code, String title) throws IOException {
        response.setStatus(status);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":" + status
                + ",\"title\":\"" + title + "\",\"code\":\"" + code + "\"}");
    }
}
