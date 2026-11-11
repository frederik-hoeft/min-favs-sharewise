package de.hsh.favs.sharewise.shared.infra.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.hsh.favs.sharewise.shared.infra.web.ApiExceptionHandler;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ComponentTest
class SystemHealthControllerComponentTests {

    @Test
    void returns200UpWhenAllReadinessIndicatorsPassAnd503DownWhenOneFails() throws Exception {
        AtomicBoolean dbHealthy = new AtomicBoolean(true);
        SystemHealthController controller = new SystemHealthController(List.of(dbHealthy::get));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/api/system/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        dbHealthy.set(false);

        mockMvc.perform(get("/api/system/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }
}
