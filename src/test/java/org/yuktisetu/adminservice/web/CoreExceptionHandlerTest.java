package org.yuktisetu.adminservice.web;

import org.yuktisetu.core.exception.CoreExceptionHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The advice's contract: a request the CLIENT got wrong answers with the right 4xx
 * and a stable error code, and only a genuine server fault is a 500.
 */
class CoreExceptionHandlerTest {

    enum Colour { RED, GREEN }

    record Payload(int count) {}

    @RestController
    static class Probe {
        @PostMapping("/login")
        String login() { return "ok"; }

        @GetMapping("/by-colour")
        String byColour(@RequestParam Colour colour) { return colour.name(); }

        @PostMapping("/body")
        String body(@RequestBody Payload p) { return "ok"; }

        @GetMapping("/boom")
        String boom() { throw new IllegalStateException("kaboom"); }
    }

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new CoreExceptionHandler()).build();
    }

    @Test
    @DisplayName("GET on a POST-only endpoint is a 405 with an Allow header, not a 500")
    void wrongMethodIs405() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("an enum parameter given garbage (e.g. [object Object]) is a 400 naming the parameter")
    void badEnumParameterIs400() throws Exception {
        mvc.perform(get("/by-colour").param("colour", "[object Object]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.error.message").value("The value given for 'colour' is not valid."));
    }

    @Test
    @DisplayName("a missing required parameter is a 400")
    void missingParameterIs400() throws Exception {
        mvc.perform(get("/by-colour")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unparseable or mistyped body is a 400")
    void unreadableBodyIs400() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"count\":\"many\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unsupported media type is a 415")
    void unsupportedMediaTypeIs415() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("a genuine server fault is still a 500 with a generic message")
    void realFaultStaysA500() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Something went wrong"));
    }
}
