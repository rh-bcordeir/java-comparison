package com.example.benchmark.reactive;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Path("/reactive")
@Produces(MediaType.APPLICATION_JSON)
public class ReactiveResource {

    // CPU-bound work must NOT run on the event loop. Offload to a worker pool.
    private static final Executor CPU_POOL =
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    @GET
    @Path("/hello")
    public Uni<Map<String, String>> hello() {
        return Uni.createFrom().item(Map.of("message", "hello", "mode", "reactive"));
    }

    @GET
    @Path("/cpu")
    public Uni<Map<String, Object>> cpu() {
        return Uni.createFrom()
                .item(() -> {
                    Map<String, Object> m = Map.of(
                            "n", Workloads.FIB_N,
                            "result", Workloads.fib(Workloads.FIB_N));
                    return m;
                })
                .runSubscriptionOn(CPU_POOL);
    }

    @GET
    @Path("/memory")
    public Uni<Map<String, Object>> memory() {
        // The fill loop takes several ms — offload to the worker pool to avoid blocking the event loop.
        return Uni.createFrom().item(() -> {
            long start = System.nanoTime();
            long checksum = Workloads.allocateAndChecksum(Workloads.MEM_BYTES);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000; //converte para ms
            Map<String, Object> m = Map.of(
                    "bytes_allocated", Workloads.MEM_BYTES,
                    "checksum", checksum,
                    "elapsed_ms", elapsedMs);
            return m;
        }).runSubscriptionOn(CPU_POOL);
    }
}
