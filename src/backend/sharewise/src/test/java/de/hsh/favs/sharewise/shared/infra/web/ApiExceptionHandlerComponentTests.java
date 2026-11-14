package de.hsh.favs.sharewise.shared.infra.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@ComponentTest
@SpringJUnitConfig(ApiExceptionHandlerComponentTests.TestConfig.class)
class ApiExceptionHandlerComponentTests {

    @Autowired
    OperationDispatcher dispatcher;

    @Autowired
    DispatchCounter dispatchCounter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dispatchCounter.count.set(0);
        this.mockMvc = MockMvcBuilders.standaloneSetup(new SampleController(dispatcher))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void invalidPayloadFieldsAreRejectedBeforeOperationDispatch() throws Exception {
        mockMvc.perform(post("/api/sample/v1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"amount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("required"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("amount"))
                .andExpect(jsonPath("$.fieldErrors[1].code").value("must_be_positive"));

        assertEquals(0, dispatchCounter.count.get());
    }

    @Test
    void nativeSpringMethodArgumentNotValidMapsToValidationFailedWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/sample/v1/items/spring-validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"amount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("not_blank"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must not be blank"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("amount"))
                .andExpect(jsonPath("$.fieldErrors[1].code").value("must_be_positive"))
                .andExpect(jsonPath("$.fieldErrors[1].message").value("must be greater than 0"));

        assertEquals(0, dispatchCounter.count.get());
    }

    @Test
    void nativeSpringHandlerMethodValidationMapsToValidationFailedWithParameterErrors() throws Exception {
        mockMvc.perform(get("/api/sample/v1/items/search").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("min"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must be at least 1"));

        assertEquals(0, dispatchCounter.count.get());
    }

    @Test
    void malformedJsonAndTypeMismatchReturnMalformedRequestWithoutDispatch() throws Exception {
        mockMvc.perform(post("/api/sample/v1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dinner\",\"amount\":\"not-a-number\""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("malformed_request"));

        mockMvc.perform(get("/api/sample/v1/items/not-an-int"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("malformed_request"));

        assertEquals(0, dispatchCounter.count.get());
    }

    @Test
    void expectedDomainErrorFromOperationMapsToDeclaredProblemDetail() throws Exception {
        mockMvc.perform(post("/api/sample/v1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"duplicate\",\"amount\":1500}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("item_conflict"))
                .andExpect(jsonPath("$.detail").value("An item with this name already exists."));

        assertEquals(1, dispatchCounter.count.get());
    }

    @Test
    void unhandledExceptionReturnsGeneric500WithoutLeakingInternalDetails() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/sample/v1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"explode\",\"amount\":1500}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andReturn();

        String body = mvcResult.getResponse().getContentAsString();
        assertFalse(body.contains("IllegalStateException"));
        assertFalse(body.contains("secret_sql_table"));
        assertEquals(1, dispatchCounter.count.get());
    }

    static final class DispatchCounter {
        final AtomicInteger count = new AtomicInteger();
    }

    enum SampleDomainError {
        CONFLICT
    }

    record CreateItemRequest(String name, int amount) {
        void validate() {
            List<FieldErrorDetail> errors = new ArrayList<>();
            if (name == null || name.isBlank()) {
                errors.add(new FieldErrorDetail("name", "required", "must not be blank"));
            }
            if (amount <= 0) {
                errors.add(new FieldErrorDetail("amount", "must_be_positive", "must be greater than 0"));
            }
            if (!errors.isEmpty()) {
                throw new RequestValidationException(errors);
            }
        }
    }

    record SpringValidatedItemRequest(String name, int amount) {
    }

    static final class SpringValidatedItemRequestValidator implements Validator {
        @Override
        public boolean supports(Class<?> clazz) {
            return SpringValidatedItemRequest.class.isAssignableFrom(clazz);
        }

        @Override
        public void validate(Object target, Errors errors) {
            SpringValidatedItemRequest request = (SpringValidatedItemRequest) target;
            if (request.name() == null || request.name().isBlank()) {
                errors.rejectValue("name", "NotBlank", "must not be blank");
            }
            if (request.amount() <= 0) {
                errors.rejectValue("amount", "must_be_positive", "must be greater than 0");
            }
        }
    }

    record ItemResponse(String name, int amount) {
    }

    static final class SampleOperationService {
        private final DispatchCounter counter;

        SampleOperationService(DispatchCounter counter) {
            this.counter = counter;
        }

        Result<ItemResponse, SampleDomainError> create(String name, int amount) {
            counter.count.incrementAndGet();
            if ("duplicate".equals(name)) {
                return Result.err(SampleDomainError.CONFLICT);
            }
            if ("explode".equals(name)) {
                throw new IllegalStateException("SELECT * FROM secret_sql_table failed");
            }
            return Result.ok(new ItemResponse(name, amount));
        }
    }

    @RestController
    @RequestMapping("/api/sample/v1/items")
    static final class SampleController {
        private final OperationDispatcher dispatcher;

        SampleController(OperationDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @InitBinder("springValidatedItemRequest")
        void initBinder(WebDataBinder binder) {
            binder.addValidators(new SpringValidatedItemRequestValidator());
        }

        @PostMapping
        ResponseEntity<?> create(@RequestBody CreateItemRequest request) {
            request.validate();
            Result<ItemResponse, SampleDomainError> outcome = dispatcher.dispatch(
                    SampleOperationService.class,
                    service -> service.create(request.name(), request.amount()));
            return outcome.fold(
                    item -> ResponseEntity.status(HttpStatus.CREATED).body(item),
                    error -> switch (error) {
                        case CONFLICT -> ApiProblems.response(
                                HttpStatus.CONFLICT,
                                "item_conflict",
                                "Item Conflict",
                                "An item with this name already exists.");
                    });
        }

        @PostMapping("/spring-validated")
        ResponseEntity<?> createSpringValidated(@Validated @RequestBody SpringValidatedItemRequest request) {
            Result<ItemResponse, SampleDomainError> outcome = dispatcher.dispatch(
                    SampleOperationService.class,
                    service -> service.create(request.name(), request.amount()));
            return outcome.fold(
                    item -> ResponseEntity.status(HttpStatus.CREATED).body(item),
                    error -> {
                        throw new IllegalStateException(error.name());
                    });
        }

        @GetMapping("/search")
        ResponseEntity<String> search(@RequestParam("limit") int limit) throws NoSuchMethodException {
            if (limit < 1) {
                Method method = SampleController.class.getDeclaredMethod("search", int.class);
                MethodParameter parameter = new MethodParameter(method, 0);
                MessageSourceResolvable error = new DefaultMessageSourceResolvable(
                        new String[]{"Min.search.limit", "Min"},
                        new Object[]{1},
                        "must be at least 1");
                ParameterValidationResult paramResult = new ParameterValidationResult(
                        parameter,
                        Integer.valueOf(limit),
                        List.of(error),
                        null,
                        null,
                        null,
                        (resolvable, type) -> null);
                MethodValidationResult validationResult = MethodValidationResult.create(
                        this,
                        method,
                        List.of(paramResult));
                throw new HandlerMethodValidationException(validationResult);
            }
            return ResponseEntity.ok("limit-" + limit);
        }

        @GetMapping("/{id}")
        ResponseEntity<String> getById(@PathVariable int id) {
            return ResponseEntity.ok("item-" + id);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "de.hsh.favs.sharewise.shared.infra.di")
    static class TestConfig {
        @Bean
        DispatchCounter dispatchCounter() {
            return new DispatchCounter();
        }

        @Bean
        @Scope("operation")
        SampleOperationService sampleOperationService(DispatchCounter counter) {
            return new SampleOperationService(counter);
        }
    }
}
