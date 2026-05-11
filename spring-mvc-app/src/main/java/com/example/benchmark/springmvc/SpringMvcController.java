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

    @GetMapping("/io")
    public Map<String, Object> io() throws InterruptedException {
        Thread.sleep(Workloads.IO_DELAY_MS);
        return Map.of("waited_ms", Workloads.IO_DELAY_MS);
    }
}
