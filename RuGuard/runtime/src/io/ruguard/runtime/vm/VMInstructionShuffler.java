package io.ruguard.runtime.vm;

/**
 * Computes deterministic opcode shuffling using the buildSeed to support P5 (Polymorphic ISA).
 */
public final class VMInstructionShuffler {

    private VMInstructionShuffler() {}

    /**
     * Creates a shuffled mapping for 256 indices using the buildSeed as the PRNG seed.
     */
    public static int[] getOpcodeMap(byte[] buildSeed) {
        int[] map = new int[256];
        for (int i = 0; i < 256; i++) {
            map[i] = i;
        }

        if (buildSeed == null || buildSeed.length == 0) {
            return map;
        }

        // Convert the 8-byte seed into a long
        long seed = 0;
        for (int i = 0; i < 8 && i < buildSeed.length; i++) {
            seed = (seed << 8) | (buildSeed[i] & 0xFF);
        }

        java.util.Random rnd = new java.util.Random(seed);

        // Fisher-Yates shuffle
        for (int i = 255; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int temp = map[i];
            map[i] = map[j];
            map[j] = temp;
        }

        return map;
    }
}
