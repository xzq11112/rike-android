package app.rike.offline;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Versioned binary envelope. Only authenticated ciphertext is ever persisted.
 * Argon2 parameters are fixed for v1; untrusted files cannot request arbitrary work.
 * AES-GCM is provided by Android/JCA, Argon2id by Bouncy Castle.
 */
public final class VaultCrypto {
    public static final int MAX_PLAINTEXT = 16 * 1024 * 1024;
    public static final int MAX_FILE = MAX_PLAINTEXT + 8192;
    private static final byte[] MAGIC = "RIKEV001".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT = 16, NONCE = 12, KEY = 32, WRAP = NONCE + KEY + 16;
    private static final int HEADER = MAGIC.length + SALT + WRAP + WRAP;
    private static final int BLOCK = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final class Session implements AutoCloseable {
        private final byte[] key, header;
        private boolean closed;
        Session(byte[] key, byte[] header) { this.key = key; this.header = header; }
        public synchronized boolean isClosed() { return closed; }
        @Override public synchronized void close() { Arrays.fill(key, (byte) 0); closed = true; }
        private void check() { if (closed) throw new IllegalStateException("资料库已锁定"); }
    }
    /** A short copy avoids UI locking behind a long worker encryption operation. */
    static Session duplicate(Session session) {
        synchronized(session){session.check();return new Session(session.key.clone(),session.header.clone());}
    }
    public static final class Created {
        public final Session session; public final String recoveryCode;
        Created(Session s, String r) { session = s; recoveryCode = r; }
    }
    public static final class Opened {
        public final Session session; public final byte[] plaintext;
        Opened(Session s, byte[] p) { session = s; plaintext = p; }
    }
    private static byte[] random(int n) { byte[] b = new byte[n]; RANDOM.nextBytes(b); return b; }
    private static byte[] concat(byte[]... parts) {
        int n = 0; for (byte[] p : parts) n += p.length;
        ByteBuffer b = ByteBuffer.allocate(n); for (byte[] p : parts) b.put(p); return b.array();
    }
    public static void requirePassword(char[] password) {
        // No minimum length or character composition policy. Keep the existing
        // input bound as a resource limit; never truncate or normalize old secrets.
        if (password.length == 0) throw new IllegalArgumentException("主密码不能为空。");
        if (password.length > 1024) throw new IllegalArgumentException("密码过长。");
    }
    private static byte[] derive(char[] password, byte[] salt) {
        if (password.length > 1024) throw new IllegalArgumentException("密码过长");
        Argon2BytesGenerator g = new Argon2BytesGenerator();
        g.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt)
            .withMemoryAsKB(65536).withIterations(3).withParallelism(1).build());
        byte[] key = new byte[KEY]; g.generateBytes(password, key); return key;
    }
    private static byte[] seal(byte[] key, byte[] plaintext, byte[] aad) throws GeneralSecurityException {
        byte[] nonce = random(NONCE);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(aad); return concat(nonce, c.doFinal(plaintext));
    }
    private static byte[] unseal(byte[] key, byte[] ciphertext, byte[] aad) throws GeneralSecurityException {
        if (ciphertext.length < NONCE + 16) throw new GeneralSecurityException("文件不完整");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, ciphertext, 0, NONCE));
        c.updateAAD(aad); return c.doFinal(ciphertext, NONCE, ciphertext.length - NONCE);
    }
    private static byte[] passwordAad(byte[] salt) { return concat("password".getBytes(StandardCharsets.US_ASCII), salt); }
    private static byte[] recoveryAad() { return "recovery".getBytes(StandardCharsets.US_ASCII); }
    public static Created create(char[] password) throws GeneralSecurityException {
        requirePassword(password);
        byte[] key = random(KEY), salt = random(SALT), recovery = random(KEY), derived = derive(password, salt);
        try {
            byte[] header = concat(MAGIC, salt, seal(derived, key, passwordAad(salt)), seal(recovery, key, recoveryAad()));
            StringBuilder code = new StringBuilder();
            for (byte b : recovery) code.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return new Created(new Session(key, header), code.toString());
        } catch (GeneralSecurityException e) { Arrays.fill(key, (byte) 0); throw e; }
        finally { Arrays.fill(derived, (byte) 0); Arrays.fill(recovery, (byte) 0); }
    }
    public static byte[] encrypt(Session session, byte[] plaintext) throws GeneralSecurityException {
        synchronized (session) {
            session.check();
            if (plaintext.length > MAX_PLAINTEXT) throw new VaultCapacityException(plaintext.length);
            int paddedSize = ((plaintext.length + 4 + BLOCK - 1) / BLOCK) * BLOCK;
            byte[] padded = random(paddedSize);
            ByteBuffer.wrap(padded).putInt(plaintext.length).put(plaintext);
            try { return concat(session.header, seal(session.key, padded, session.header)); }
            finally { Arrays.fill(padded, (byte) 0); }
        }
    }
    private static byte[] header(byte[] file) throws GeneralSecurityException {
        if (file.length < HEADER + NONCE + 16 + 4 || file.length > MAX_FILE ||
            !Arrays.equals(MAGIC, Arrays.copyOf(file, MAGIC.length))) throw new GeneralSecurityException("无法识别备份");
        return Arrays.copyOf(file, HEADER);
    }
    private static Opened decode(byte[] file, byte[] header, byte[] key) throws GeneralSecurityException {
        byte[] padded = null;
        try {
            padded = unseal(key, Arrays.copyOfRange(file, HEADER, file.length), header);
            if (padded.length < 4) throw new GeneralSecurityException("文件不完整");
            int n = ByteBuffer.wrap(padded).getInt();
            if (n < 0 || n > MAX_PLAINTEXT || n > padded.length - 4) throw new GeneralSecurityException("文件不完整");
            return new Opened(new Session(key, header), Arrays.copyOfRange(padded, 4, n + 4));
        } catch (GeneralSecurityException e) { Arrays.fill(key, (byte) 0); throw e; }
        finally { if (padded != null) Arrays.fill(padded, (byte) 0); }
    }
    /** Only pass this copy directly into an authenticated Keystore wrapping operation. */
    static byte[] biometricKey(Session session) {
        synchronized (session) { session.check(); return session.key.clone(); }
    }
    static Opened openWithBiometricKey(byte[] file, byte[] key) throws GeneralSecurityException {
        if (key.length != KEY) throw new GeneralSecurityException("Invalid key");
        return decode(file, header(file), key.clone());
    }
    public static Opened open(byte[] file, char[] password) throws GeneralSecurityException {
        byte[] h = header(file), salt = Arrays.copyOfRange(h, 8, 24), derived = derive(password, salt);
        try { return decode(file, h, unseal(derived, Arrays.copyOfRange(h, 24, 24 + WRAP), passwordAad(salt))); }
        finally { Arrays.fill(derived, (byte) 0); }
    }
    public static Opened recover(byte[] file, String code) throws GeneralSecurityException {
        byte[] h = header(file);
        String normalized = code.replace(" ", "").replace("-", "").trim();
        if (!normalized.matches("[0-9a-fA-F]{64}")) throw new GeneralSecurityException("恢复密钥格式不正确");
        byte[] recovery = new byte[KEY];
        for (int i = 0; i < KEY; i++) recovery[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
        try { return decode(file, h, unseal(recovery, Arrays.copyOfRange(h, 24 + WRAP, HEADER), recoveryAad())); }
        finally { Arrays.fill(recovery, (byte) 0); }
    }
    /** Rewrap the random data key; recovery code remains valid. Old backups retain their old password. */
    public static Session changePassword(Session session, char[] password) throws GeneralSecurityException {
        requirePassword(password);
        byte[] salt = random(SALT), derived = derive(password, salt);
        try {
            synchronized (session) {
                session.check();
                byte[] h = concat(MAGIC, salt, seal(derived, session.key, passwordAad(salt)),
                    Arrays.copyOfRange(session.header, 24 + WRAP, HEADER));
                return new Session(session.key.clone(), h);
            }
        } finally { Arrays.fill(derived, (byte) 0); }
    }
}
