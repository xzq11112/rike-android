package app.rike.offline;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local-only access. The non-exportable device key never belongs in a backup.
 * The vault header binds this wrapper to one password/recovery configuration. */
final class DeviceVault {
    static final String ALIAS = "rike.device.v1";
    private static final int FINGERPRINT = 32, NONCE = 12, WRAPPED_KEY = 48;
    private static final int NONCE_OFFSET = 2 + FINGERPRINT;
    private static final int KEY_OFFSET = NONCE_OFFSET + NONCE;
    private static final int ENVELOPE = KEY_OFFSET + WRAPPED_KEY;

    interface Keys {
        SecretKey create(String alias) throws Exception;
        SecretKey get(String alias) throws Exception;
        void remove(String alias) throws Exception;
    }

    private final AtomicFile file;
    private final Keys keys;

    DeviceVault(Context context) {
        this(new AtomicFile(new File(context.getNoBackupFilesDir(), "device.key")), new Keys() {
            private KeyStore store() throws Exception {
                KeyStore store = KeyStore.getInstance("AndroidKeyStore");
                store.load(null);
                return store;
            }
            public SecretKey get(String alias) throws Exception {
                return (SecretKey) store().getKey(alias, null);
            }
            public void remove(String alias) throws Exception { store().deleteEntry(alias); }
            public SecretKey create(String alias) throws Exception {
                KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false).build());
                return generator.generateKey();
            }
        });
    }

    DeviceVault(AtomicFile file, Keys keys) { this.file = file; this.keys = keys; }

    synchronized boolean enabled() {
        return file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists();
    }

    private static String alias(byte[] envelope) throws IOException {
        if (envelope.length != ENVELOPE || envelope[0] != 1 ||
            (envelope[1] != 1 && envelope[1] != 2)) throw new IOException("Invalid device envelope");
        return ALIAS + (envelope[1] == 1 ? ".a" : ".b");
    }

    private byte[] envelope() throws IOException {
        byte[] bytes = new byte[ENVELOPE];
        try (InputStream in = file.openRead()) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = in.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("Incomplete device envelope");
                offset += count;
            }
            if (in.read() != -1) throw new IOException("Invalid device envelope");
        }
        alias(bytes);
        return bytes;
    }

    private boolean installed(byte[] expected) {
        File base = file.getBaseFile();
        // openRead() can restore .bak and mutate the file. Never use it while
        // deciding whether an unfinished writer must still be rolled back.
        if (new File(base + ".bak").exists() || new File(base + ".new").exists()) return false;
        try (InputStream in = new FileInputStream(base)) {
            for (byte value : expected) if (in.read() != (value & 255)) return false;
            return in.read() == -1;
        } catch (IOException ignored) { return false; }
    }

    synchronized boolean matches(byte[] vault) {
        try {
            return MessageDigest.isEqual(Arrays.copyOfRange(envelope(), 2, NONCE_OFFSET),
                VaultCrypto.headerFingerprint(vault));
        } catch (Exception ignored) { return false; }
    }

    synchronized void enroll(VaultCrypto.Session session) throws Exception {
        String old = enabled() ? alias(envelope()) : null;
        String candidate = ALIAS + ((ALIAS + ".a").equals(old) ? ".b" : ".a");
        byte[] key = VaultCrypto.biometricKey(session);
        byte[] installed = null;
        FileOutputStream out = null;
        boolean committed = false;
        try {
            byte[] fingerprint = VaultCrypto.headerFingerprint(session);
            SecretKey wrapper = keys.create(candidate);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, wrapper);
            cipher.updateAAD(fingerprint);
            byte[] wrapped = cipher.doFinal(key);
            if (cipher.getIV().length != NONCE || wrapped.length != WRAPPED_KEY)
                throw new GeneralSecurityException("Invalid device wrapping result");
            installed = new byte[ENVELOPE];
            installed[0] = 1;
            installed[1] = (byte) (candidate.endsWith(".a") ? 1 : 2);
            System.arraycopy(fingerprint, 0, installed, 2, FINGERPRINT);
            System.arraycopy(cipher.getIV(), 0, installed, NONCE_OFFSET, NONCE);
            System.arraycopy(wrapped, 0, installed, KEY_OFFSET, WRAPPED_KEY);
            out = file.startWrite();
            out.write(installed);
            file.finishWrite(out);
            out = null;
            committed = true;
        } catch (Exception failure) {
            // A completed atomic installation stays usable even if a file
            // implementation reports an error after its commit point.
            // In the legacy first-write case, complete bytes already occupy
            // the base file before finishWrite() syncs/closes the descriptor.
            // An open descriptor therefore cannot prove a completed commit.
            if (installed != null && out != null) try {
                committed = !out.getFD().valid() && installed(installed);
            } catch (IOException ignored) {}
            if (!committed) {
                if (out != null) try { file.failWrite(out); }
                    catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                try { keys.remove(candidate); }
                    catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } finally { Arrays.fill(key, (byte) 0); }
        // Deleting an obsolete alias is best effort after the commit point;
        // cleanup failure must not delete or invalidate the installed key.
        if (old != null && !old.equals(candidate)) try { keys.remove(old); }
            catch (Exception ignored) {}
    }

    synchronized VaultCrypto.Opened open(byte[] vault) throws Exception {
        byte[] envelope = envelope();
        byte[] fingerprint = VaultCrypto.headerFingerprint(vault);
        if (!MessageDigest.isEqual(Arrays.copyOfRange(envelope, 2, NONCE_OFFSET), fingerprint))
            throw new GeneralSecurityException("Device key does not match this vault");
        SecretKey wrapper = keys.get(alias(envelope));
        if (wrapper == null) throw new GeneralSecurityException("Device key unavailable");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, wrapper,
            new GCMParameterSpec(128, envelope, NONCE_OFFSET, NONCE));
        cipher.updateAAD(fingerprint);
        byte[] key = cipher.doFinal(envelope, KEY_OFFSET, WRAPPED_KEY);
        try { return VaultCrypto.openWithBiometricKey(vault, key); }
        finally { Arrays.fill(key, (byte) 0); }
    }

    synchronized void disable() throws Exception {
        file.delete();
        Exception failure = null;
        for (String alias : new String[] { ALIAS + ".a", ALIAS + ".b" }) {
            try { keys.remove(alias); }
            catch (Exception cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }
}
