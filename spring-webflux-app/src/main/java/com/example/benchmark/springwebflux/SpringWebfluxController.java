package com.example.benchmark.springwebflux;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    @GetMapping("/memory")
    public Mono<Map<String, Object>> memory() {
        // Offload to the parallel scheduler — the fill loop blocks for several ms.
        return Mono.fromCallable(() -> {
            long start = System.nanoTime();
            long checksum = Workloads.allocateAndChecksum(Workloads.MEM_BYTES);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000; //converte para ms
            Map<String, Object> m = Map.of(
                    "bytes_allocated", Workloads.MEM_BYTES,
                    "checksum", checksum,
                    "elapsed_ms", elapsedMs);
            return m;
        }).subscribeOn(Schedulers.parallel());
    }
}
