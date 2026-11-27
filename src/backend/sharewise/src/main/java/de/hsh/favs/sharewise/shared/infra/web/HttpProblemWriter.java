package de.hsh.favs.sharewise.shared.infra.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Serializes the same problem contract as MVC for security failures occurring before dispatch. */
@Component
public final class HttpProblemWriter {
    private final JacksonJsonHttpMessageConverter converter;

    public HttpProblemWriter(JsonMapper mapper) {
        // The converter's builder constructor installs Spring's flattened ProblemDetail mixin.
        converter = new JacksonJsonHttpMessageConverter(mapper.rebuild());
    }

    public void write(HttpServletResponse response, ResponseEntity<ProblemDetail> problem) throws IOException {
        response.setStatus(problem.getStatusCode().value());
        ServletServerHttpResponse output = new ServletServerHttpResponse(response);
        output.getHeaders().putAll(problem.getHeaders());
        output.getHeaders().setCacheControl("no-store");
        converter.write(problem.getBody(), MediaType.APPLICATION_PROBLEM_JSON, output);
        output.flush();
    }

    public void write(HttpServletResponse response, int status, String code, String title) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.valueOf(status));
        problem.setTitle(title);
        problem.setProperty(ApiProblems.CODE_PROPERTY, code);
        write(response, ApiProblems.toResponse(problem));
    }
}
