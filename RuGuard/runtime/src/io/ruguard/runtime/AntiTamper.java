package io.ruguard.runtime;

import java.lang.management.ManagementFactory;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Runtime self-protection: anti-debug, anti-VM/sandbox, and integrity
 * enforcement. All failures collapse into a single opaque "Invalid payload"
 * abort so an attacker can never tell <em>which</em> check tripped.
 *
 * <p>Every probe is intentionally cheap and side-effect free until a positive
 * detection, at which point {@link #fail()} terminates the process hard. The
 * checks can be disabled for local debugging with
 * {@code -Druguard.checks=off} (this switch is meaningless in a shipped build
 * because the property name is string-encrypted away by the CLI).
 */
public final class AntiTamper {

    private AntiTamper() {}

    private static volatile boolean armed = false;

    /** MAC-address OUI prefixes used by common hypervisors. */
    private static final byte[][] VM_MAC_PREFIXES = {
            {0x00, 0x05, 0x69},             // VMware
            {0x00, 0x0C, 0x29},             // VMware
            {0x00, 0x1C, 0x14},             // VMware
            {0x00, 0x50, 0x56},             // VMware ESX
            {0x08, 0x00, 0x27},             // VirtualBox
            {0x00, 0x16, 0x3E},             // Xen
            {0x00, 0x1C, 0x42},             // Parallels
            {0x52, 0x54, 0x00},             // QEMU/KVM
    };

    private static final String[] VM_MARKERS = {
            "vmware", "virtualbox", "vbox", "qemu", "kvm", "xen", "hyper-v",
            "hyperv", "parallels", "sandbox", "cuckoo", "wine"
    };

    /**
     * Runs the full protection sweep. Safe to call multiple times; the heavy
     * work runs only once. Any positive detection aborts via {@link #fail()}.
     */
    public static void enforce() {
        if (armed) return;
        armed = true;
        if (disabled()) return;
        try {
            if (detectDebugger()) fail();
            if (detectTimingBreakpoint()) fail();
            if (detectVirtualMachine()) fail();
        } catch (Throwable t) {
            // A probe that throws is itself suspicious tampering.
            fail();
        }
    }

    /** Lightweight probe suitable for hot paths (called from the dispatcher). */
    public static void quickCheck() {
        if (disabled()) return;
        if (detectDebugger()) fail();
    }

    /** Uniform abort for both integrity failures and self-protection hits. */
    public static void fail() {
        try {
            System.err.println("Invalid payload");
        } catch (Throwable ignored) {}
        // Corrupt the runtime entropy so any in-flight decrypt yields garbage,
        // then terminate hard (Runtime.halt bypasses shutdown hooks/finally).
        try {
            RuntimeContext.current().shutdown();
        } catch (Throwable ignored) {}
        Runtime.getRuntime().halt(0x2A);
        // Unreachable, but keeps the verifier happy on any inlining path.
        throw new Error("Invalid payload");
    }

    // ── Anti-debug ─────────────────────────────────────────────────────────

    private static boolean detectDebugger() {
        try {
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                String a = arg.toLowerCase(Locale.ROOT);
                if (a.contains("-agentlib:jdwp") || a.contains("-xrunjdwp")
                        || a.contains("-xdebug") || a.contains("jdwp")
                        || a.contains("-javaagent") || a.contains("-agentpath")) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return true;
        }
        // JDWP transport leaves these system properties behind.
        return System.getProperty("sun.jvm.args", "").toLowerCase(Locale.ROOT).contains("jdwp")
                || System.getProperty("jdwp") != null;
    }

    /**
     * A debugger single-stepping or sitting on a breakpoint dilates wall-clock
     * time far beyond the CPU cost of a trivial loop. If a few thousand
     * additions take absurdly long, someone is watching.
     */
    private static boolean detectTimingBreakpoint() {
        long start = System.nanoTime();
        long acc = 0;
        for (int i = 0; i < 200_000; i++) acc += (i ^ acc) + 1;
        long elapsed = System.nanoTime() - start;
        // Keep the loop from being optimised away.
        if (acc == 0x1234_5678L) System.out.print("");
        // 200k trivial ops should finish well under ~50ms even on slow HW.
        return elapsed > 200_000_000L;
    }

    // ── Anti-VM / sandbox ──────────────────────────────────────────────────

    private static boolean detectVirtualMachine() {
        if (matchMarkers(System.getProperty("os.name")) ) return true;
        if (matchMarkers(System.getProperty("os.version"))) return true;
        if (matchMarkers(System.getProperty("user.name"))) return true;
        if (matchMarkers(System.getenv("COMPUTERNAME"))) return true;
        if (matchMarkers(System.getenv("USERNAME"))) return true;
        try {
            if (matchMarkers(java.net.InetAddress.getLocalHost().getHostName())) return true;
        } catch (Throwable ignored) {}
        return hasHypervisorMac();
    }

    private static boolean hasHypervisorMac() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                byte[] mac = ni.getHardwareAddress();
                if (mac == null || mac.length < 3) continue;
                for (byte[] p : VM_MAC_PREFIXES) {
                    if (mac[0] == p[0] && mac[1] == p[1] && mac[2] == p[2]) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean matchMarkers(String value) {
        if (value == null) return false;
        String v = value.toLowerCase(Locale.ROOT);
        for (String m : VM_MARKERS) {
            if (v.contains(m)) return true;
        }
        return false;
    }

    private static boolean disabled() {
        return "off".equalsIgnoreCase(System.getProperty("ruguard.checks", ""));
    }
}
