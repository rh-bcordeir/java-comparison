package com.example.benchmark.springwebflux;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest
@AutoConfigureWebTestClient
class SpringWebfluxControllerTest {

    @Autowired WebTestClient client;

    @Test
    void hello() {
        client.get().uri("/spring-webflux/hello").exchange()
              .expectStatus().isOk()
              .expectBody()
              .jsonPath("$.mode").isEqualTo("spring-webflux")
              .jsonPath("$.message").isEqualTo("hello");
    }

    @Test
    void cpu() {
        client.get().uri("/spring-webflux/cpu").exchange()
              .expectStatus().isOk()
              .expectBody()
              .jsonPath("$.n").isEqualTo(35)
              .jsonPath("$.result").isEqualTo(9227465);
    }

    @Test
    void memory() {
        client.get().uri("/spring-webflux/memory").exchange()
              .expectStatus().isOk()
              .expectBody()
              .jsonPath("$.bytes_allocated").isEqualTo(10 * 1024 * 1024);
    }
}
