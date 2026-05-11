package com.example.benchmark.imperative;

final class Workloads {
    static final int FIB_N = 35;
    static final int MEM_BYTES = 10 * 1024 * 1024; //10Mb

    private Workloads() {}

    static long fib(int n) {
        if (n < 2) return n;
        return fib(n - 1) + fib(n - 2);
    }

    /** Allocate and fill a byte[]; return a checksum so the JIT can't delete the work. */
    static long allocateAndChecksum(int size) {
        byte[] data = new byte[size];
        long sum = 0;
        for (int i = 0; i < size; i++) {
            data[i] = (byte) i;
            sum += data[i];
        }
        return sum;
    }
}
