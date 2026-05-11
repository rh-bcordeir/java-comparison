package com.example.benchmark.reactive;

final class Workloads {
    static final int FIB_N = 35;
    static final int IO_DELAY_MS = 50;

    private Workloads() {}

    static long fib(int n) {
        if (n < 2) return n;
        return fib(n - 1) + fib(n - 2);
    }
}
