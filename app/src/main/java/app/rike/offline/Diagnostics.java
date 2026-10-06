package app.rike.offline;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Deliberately cannot accept a message, Throwable, URI, record or identifier.
 * Only fixed failure codes are kept in memory. No logcat, files or networking.
 */
public final class Diagnostics {
    public enum Code {
        CAPACITY_LIMIT, STORAGE_IO, DATA_INVALID, AUTH_OR_FILE_INVALID, OPERATION_FAILED,
        VAULT_WRITE_FAILED, BACKUP_READ_FAILED, BACKUP_WRITE_FAILED,
        BIOMETRIC_UNAVAILABLE, DIAGNOSTIC_EXPORT_FAILED, SAVE_SLOW, PAGE_BUILD_SLOW,
        ORDER_COMMIT_FAILED, BACKUP_VERIFY_FAILED, RESTORE_WRITE_FAILED
    }
    static final int LIMIT = 32;
    static final long RETENTION_MS = 30 * 60 * 1000L;
    private static final class Entry {
        final Code code; final long expires;
        Entry(Code code, long expires) { this.code = code; this.expires = expires; }
    }
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final LongSupplier clock;
    public Diagnostics(LongSupplier monotonicClock) { clock = monotonicClock; }
    public synchronized void record(Code code) {
        Objects.requireNonNull(code);
        prune();
        while (entries.size() >= LIMIT) entries.removeFirst();
        entries.addLast(new Entry(code, clock.getAsLong() + RETENTION_MS));
    }
    private void prune() {
        long now = clock.getAsLong();
        while (!entries.isEmpty() && entries.peekFirst().expires <= now) entries.removeFirst();
    }
    public synchronized void clear() { entries.clear(); }
    public synchronized String report() {
        prune();
        StringBuilder result = new StringBuilder("日课诊断 v1\n应用版本：")
            .append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE)
            .append(")\n构建：").append(BuildConfig.DEBUG ? "测试版" : "Release")
            .append("\n仅固定错误码；无正文、密码、路径、时间或设备标识。\n");
        if (entries.isEmpty()) result.append("无诊断记录\n");
        for (Entry entry : entries) result.append(entry.code.name()).append('\n');
        return result.toString();
    }
}
