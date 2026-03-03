package com.mediabrowser;

import android.os.Bundle;
import android.util.Log;

/**
 * Centralized logging utility for react-native-android-media-browser.
 * Allows enabling/disabling of verbose diagnostic logs at runtime without removing code.
 *
 * Usage:
 *   MBLog.setLoggingMode(MBLog.Mode.ALL);      // All levels: method entry tracing, debug, info, warn, error
 *   MBLog.setLoggingMode(MBLog.Mode.ERROR);    // Only error logs (default)
 *   MBLog.setLoggingMode(MBLog.Mode.DISABLED); // No logs (except force)
 *
 * Example:
 *   MBLog.v(TAG, "→ onLoadChildren(parentId=" + parentId + ")");
 *   MBLog.d(TAG, "Hierarchy ready: " + store.isHierarchyReady());
 *   MBLog.e(TAG, "Failed to start foreground service", e);
 */
public final class MBLog {

    public enum Mode { ALL, ERROR, DISABLED }

    /** Default: ERROR — only errors in production; switch to ALL for debugging. */
    private static volatile Mode MODE = Mode.ERROR;

    static { Log.i("MBLog", "MBLog initialized (mode=" + MODE + ")"); }

    private MBLog() {}

    // ---- Mode control ----

    public static void setLoggingMode(Mode mode) { MODE = (mode != null ? mode : Mode.DISABLED); }
    public static Mode getLoggingMode() { return MODE; }

    // ---- Level checks ----

    private static boolean allowAll()    { return MODE == Mode.ALL; }
    private static boolean allowErrors() { return MODE == Mode.ALL || MODE == Mode.ERROR; }

    // ---- Tag helper ----

    private static String t(String rawTag) { return rawTag != null ? rawTag : "MBLog"; }

    // ---- Standard log methods ----

    /** Verbose — ALL mode only. Use for method-entry tracing (→ methodName(params)). */
    public static void v(String tag, String msg) { if (allowAll()) Log.v(t(tag), msg); }

    /** Debug — ALL mode only. */
    public static void d(String tag, String msg) { if (allowAll()) Log.d(t(tag), msg); }
    public static void d(String tag, String msg, boolean showCaller) {
        if (allowAll()) Log.d(t(tag), showCaller ? (msg + " [caller=" + callerInfo() + "]") : msg);
    }

    /** Info — ALL mode only. */
    public static void i(String tag, String msg) { if (allowAll()) Log.i(t(tag), msg); }

    /** Warning — ALL mode only. Use MBLog.e for warnings you always want to see. */
    public static void w(String tag, String msg) { if (allowAll()) Log.w(t(tag), msg); }
    public static void w(String tag, String msg, Throwable throwable) { if (allowAll()) Log.w(t(tag), msg, throwable); }

    /** Error — ALL and ERROR modes. Always visible unless DISABLED. */
    public static void e(String tag, String msg) { if (allowErrors()) Log.e(t(tag), msg); }
    public static void e(String tag, String msg, Throwable throwable) { if (allowErrors()) Log.e(t(tag), msg, throwable); }

    /** Always log regardless of mode (for critical one-time diagnostics). */
    public static void force(String tag, String msg) { Log.i(t(tag), "[FORCE] " + msg); }

    // ---- Formatting helpers ----

    /** Null-safe toString. */
    public static String safe(Object o) {
        if (o == null) return "null";
        try { return String.valueOf(o); }
        catch (Throwable t) { return o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o)); }
    }

    /** Object identity hash as hex (for distinguishing instances). */
    public static String id(Object o) {
        if (o == null) return "null";
        try { return "0x" + Integer.toHexString(System.identityHashCode(o)); }
        catch (Throwable t) { return "id{error}"; }
    }

    /** Formats a Bundle's keys and values for logging. Sensitive values are NOT redacted — use only for debug builds. */
    public static String bundleInfo(Bundle b) {
        if (b == null) return "null";
        try {
            StringBuilder sb = new StringBuilder("Bundle{");
            boolean first = true;
            for (String key : b.keySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(key).append("=").append(b.get(key));
            }
            sb.append("}");
            return sb.toString();
        } catch (Throwable t) { return "Bundle{error}"; }
    }

    /**
     * Returns a short "ClassName.methodName:line" string for the first caller
     * outside MBLog in the current stack trace. Useful for verbose tracing.
     */
    public static String callerInfo() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            String mbLogClass = MBLog.class.getName();
            String calleeClass = null;

            for (StackTraceElement e : st) {
                String cls = e.getClassName();
                if (cls == null) continue;
                if (cls.equals(mbLogClass)) continue;               // skip MBLog frames
                if (isSyntheticFrame(e)) continue;                  // skip lambdas/accessors

                if (calleeClass == null) {
                    calleeClass = cls;                               // first frame = the direct caller
                    continue;
                }

                // Return the first frame from a DIFFERENT class (i.e., who called the direct caller)
                if (!cls.equals(calleeClass)) {
                    return cls + "." + e.getMethodName() + ":" + e.getLineNumber();
                }
            }
        } catch (Throwable ignored) {}
        return "unknown";
    }

    private static boolean isSyntheticFrame(StackTraceElement e) {
        try {
            String m = e.getMethodName();
            String c = e.getClassName();
            if (e.getLineNumber() <= 0) return true;
            if (m == null) return true;
            if (m.startsWith("-$$Nest$")) return true;
            if (m.startsWith("access$")) return true;
            if (m.contains("lambda$")) return true;
            if (c != null && c.contains("$$")) return true;
        } catch (Throwable ignored) {}
        return false;
    }
}
