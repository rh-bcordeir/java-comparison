package com.example.benchmark.springwebflux;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/spring-webflux")
public class SpringWebfluxController {

    @GetMapping("/hello")
    public Mono<Map<String, String>> hello() {
        return Mono.just(Map.of("message", "hello", "mode", "spring-webflux"));
    }

    @GetMapping("/cpu")
    public Mono<Map<String, Object>> cpu() {
        // CPU work must NOT run on the Netty event loop. Schedulers.parallel() is sized to nCPUs.
        return Mono.fromCallable(() -> {
                    Map<String, Object> m = Map.of(
                            "n", Workloads.FIB_N,
                            "result", Workloads.fib(Workloads.FIB_N));
                    return m;
                })
                .subscribeOn(Schedulers.parallel());
    }

    @GetMapping("/io")
    public Mono<Map<String, Object>> io() {
        Map<String, Object> body = Map.of("waited_ms", Workloads.IO_DELAY_MS);
        return Mono.delay(Duration.ofMillis(Workloads.IO_DELAY_MS)).thenReturn(body);
    }
}
