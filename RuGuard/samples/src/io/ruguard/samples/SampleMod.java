package io.ruguard.samples;

import io.ruguard.annotation.Guarded;

public class SampleMod {
    @Guarded
    public static String greet(String name) {
        return "Hello, " + name + "!";
    }

    @Guarded(io.ruguard.annotation.Guarded.Level.STRONG)
    public static int compute(int x) {
        return x * x + 42;
    }

    @Guarded(io.ruguard.annotation.Guarded.Level.VIRTUALIZED)
    public static long fibonacci(int n) {
        if (n <= 1) return n;
        long a = 0, b = 1;
        for (int i = 2; i <= n; i++) {
            long t = a + b;
            a = b;
            b = t;
        }
        return b;
    }

    public static void main(String[] args) {
        System.out.println(greet("World"));
        System.out.println("compute(7) = " + compute(7));
        System.out.println("fib(10) = " + fibonacci(10));
        System.out.println("ALL_OK");
    }
}
