package com.example.benchmark.reactive;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Duration;
import java.util.Map;

@Path("/reactive")
@Produces(MediaType.APPLICATION_JSON)
public class ReactiveResource {

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
                    return Map.<String,Object>of(
                            "n", Workloads.FIB_N,
                            "result", Workloads.fib(Workloads.FIB_N));
                }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @GET
    @Path("/memory")
    public Uni<Map<String, Object>> memory() {
        return Uni.createFrom().item(() -> {
            long start = System.nanoTime();
            long checksum = Workloads.allocateAndChecksum(Workloads.MEM_BYTES);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return Map.<String, Object>of(
                    "bytes_allocated", Workloads.MEM_BYTES,
                    "checksum", checksum,
                    "elapsed_ms", elapsedMs);
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @GET
    @Path("/io")
    public Uni<Map<String, Object>> io() {
        return Uni.createFrom().item(Map.<String, Object>of("waited_ms", 200))
                .onItem().delayIt().by(Duration.ofMillis(200));
    }
}
