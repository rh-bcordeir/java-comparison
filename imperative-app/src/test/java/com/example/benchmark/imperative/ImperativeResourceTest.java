package com.example.benchmark.imperative;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ImperativeResourceTest {

    @Test
    void hello() {
        given().when().get("/imperative/hello")
                .then().statusCode(200)
                .body("message", equalTo("hello"))
                .body("mode", equalTo("imperative"));
    }

    @Test
    void cpu() {
        given().when().get("/imperative/cpu")
                .then().statusCode(200)
                .body("n", equalTo(35))
                .body("result", equalTo(9227465));
    }

    @Test
    void memory() {
        given().when().get("/imperative/memory")
                .then().statusCode(200)
                .body("bytes_allocated", equalTo(10 * 1024 * 1024));
    }
}
