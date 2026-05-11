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
    void io() {
        long start = System.currentTimeMillis();
        given().when().get("/imperative/io")
                .then().statusCode(200)
                .body("waited_ms", equalTo(50));
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed < 50) {
            throw new AssertionError("io endpoint returned in " + elapsed + " ms, expected >= 50");
        }
    }
}
