package com.example.benchmark.springmvc;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SpringMvcControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void hello() throws Exception {
        mvc.perform(get("/spring-mvc/hello"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.mode").value("spring-mvc"))
           .andExpect(jsonPath("$.message").value("hello"));
    }

    @Test
    void cpu() throws Exception {
        mvc.perform(get("/spring-mvc/cpu"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.n").value(35))
           .andExpect(jsonPath("$.result").value(9227465));
    }

    @Test
    void io() throws Exception {
        long start = System.currentTimeMillis();
        mvc.perform(get("/spring-mvc/io"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.waited_ms").value(50));
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed < 50) throw new AssertionError("io returned in " + elapsed + " ms");
    }
}
