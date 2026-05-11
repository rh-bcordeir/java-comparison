package com.example.benchmark.imperative;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

@Path("/imperative")
@Produces(MediaType.APPLICATION_JSON)
public class ImperativeResource {

    @GET
    @Path("/hello")
    public Map<String, String> hello() {
        return Map.of("message", "hello", "mode", "imperative");
    }

    @GET
    @Path("/cpu")
    public Map<String, Object> cpu() {
        long result = Workloads.fib(Workloads.FIB_N);
        return Map.of("n", Workloads.FIB_N, "result", result);
    }

    @GET
    @Path("/io")
    public Map<String, Object> io() throws InterruptedException {
        Thread.sleep(Workloads.IO_DELAY_MS);
        return Map.of("waited_ms", Workloads.IO_DELAY_MS);
    }
}
