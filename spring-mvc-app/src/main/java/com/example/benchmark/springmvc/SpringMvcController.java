package com.example.benchmark.springmvc;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/spring-mvc")
public class SpringMvcController {

    @GetMapping("/hello")
    public Map<String, String> hello() {
        return Map.of("message", "hello", "mode", "spring-mvc");
    }

    @GetMapping("/cpu")
    public Map<String, Object> cpu() {
        long result = Workloads.fib(Workloads.FIB_N);
        return Map.of("n", Workloads.FIB_N, "result", result);
    }

    @GetMapping("/memory")
    public Map<String, Object> memory() {
        long start = System.nanoTime();
        long checksum = Workloads.allocateAndChecksum(Workloads.MEM_BYTES);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return Map.of(
                "bytes_allocated", Workloads.MEM_BYTES,
                "checksum", checksum,
                "elapsed_ms", elapsedMs);
    }
}
