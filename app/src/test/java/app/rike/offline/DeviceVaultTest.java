package app.rike.offline;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic JCA wrapping keys exercise persistence, binding, and transactions.
 * These tests do not claim hardware Keystore coverage. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 28)
public class DeviceVaultTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static class Keys implements DeviceVault.Keys {
        final Map<String, SecretKey> values = new HashMap<>();
        boolean rejectCreate;
        String rejectRemove;
        public SecretKey create(String alias) throws Exception {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            SecretKey key = generator.generateKey();
            values.put(alias, key);
            if (rejectCreate) throw new IOException("Synthetic create failure");
            return key;
        }
        public SecretKey get(String alias) { return values.get(alias); }
        public void remove(String alias) throws Exception {
            if (alias.equals(rejectRemove)) throw new IOException("Synthetic cleanup failure");
            values.remove(alias);
        }
    }

    private static class FailingFile extends AtomicFile {
        boolean rejectStart, rejectFinish, rejectAfterCommit;
        FailingFile(File path) { super(path); }
        @Override public FileOutputStream startWrite() throws IOException {
            if (rejectStart) throw new IOException("Synthetic write failure");
            return super.startWrite();
        }
        @Override public void finishWrite(FileOutputStream out) {
            if (rejectFinish) throw new IllegalStateException("Synthetic commit failure");
            super.finishWrite(out);
            if (rejectAfterCommit) throw new IllegalStateException("Synthetic post-commit failure");
        }
    }

    private static byte[] encrypt(VaultCrypto.Session session, String text) throws Exception {
        return VaultCrypto.encrypt(session, text.getBytes(StandardCharsets.UTF_8));
    }
    private static void readable(DeviceVault device, byte[] vault, String text) throws Exception {
        assertTrue(device.matches(vault));
        VaultCrypto.Opened opened = device.open(vault);
        try { assertEquals(text, new String(opened.plaintext, StandardCharsets.UTF_8)); }
        finally { Arrays.fill(opened.plaintext, (byte) 0); opened.session.close(); }
    }

    @Test public void persistedEnvelopeOpensWithoutPasswordAndFollowsDataUpdates() throws Exception {
        Keys keys = new Keys();
        File path = new File(folder.getRoot(), "device.key");
        DeviceVault device = new DeviceVault(new AtomicFile(path), keys);
        VaultCrypto.Created created = VaultCrypto.create("synthetic".toCharArray());
        byte[] dataKey = VaultCrypto.biometricKey(created.session);
        try {
            assertFalse(device.enabled());
            device.enroll(created.session);
            byte[] wrapper = Files.readAllBytes(path.toPath());
            assertEquals(94, wrapper.length);
            for (int offset = 0; offset <= wrapper.length - dataKey.length; offset++)
                assertFalse(Arrays.equals(dataKey, Arrays.copyOfRange(wrapper, offset, offset + dataKey.length)));
            assertTrue(device.enabled());
            DeviceVault restarted = new DeviceVault(new AtomicFile(path), keys);
            readable(restarted, encrypt(created.session, "synthetic first"), "synthetic first");
            readable(restarted, encrypt(created.session, "synthetic update"), "synthetic update");
        } finally { Arrays.fill(dataKey, (byte) 0); created.session.close(); }
    }

    @Test public void changedPasswordHeaderRejectsStaleDeviceWrapperWithSameDataKey() throws Exception {
        Keys keys = new Keys();
        DeviceVault device = new DeviceVault(new AtomicFile(new File(folder.getRoot(), "device.key")), keys);
        VaultCrypto.Created created = VaultCrypto.create("synthetic old".toCharArray());
        VaultCrypto.Session changed = null;
        try {
            device.enroll(created.session);
            changed = VaultCrypto.changePassword(created.session, "synthetic new".toCharArray());
            byte[] protectedVault = encrypt(changed, "synthetic protected");
            assertFalse(device.matches(protectedVault));
            try { device.open(protectedVault); fail("Stale device key must not bypass new password"); }
            catch (GeneralSecurityException expected) {}
            readable(device, encrypt(created.session, "synthetic old"), "synthetic old");
            assertFalse(device.matches(new byte[144]));
        } finally { if (changed != null) changed.close(); created.session.close(); }
    }

    @Test public void tamperedFingerprintCannotRebindWrappedKey() throws Exception {
        Keys keys = new Keys();
        File path = new File(folder.getRoot(), "device.key");
        DeviceVault device = new DeviceVault(new AtomicFile(path), keys);
        VaultCrypto.Created created = VaultCrypto.create("synthetic old".toCharArray());
        VaultCrypto.Session changed = null;
        try {
            device.enroll(created.session);
            changed = VaultCrypto.changePassword(created.session, "synthetic new".toCharArray());
            byte[] protectedVault = encrypt(changed, "synthetic protected");
            byte[] wrapper = Files.readAllBytes(path.toPath());
            System.arraycopy(VaultCrypto.headerFingerprint(protectedVault), 0, wrapper, 2, 32);
            Files.write(path.toPath(), wrapper);
            assertTrue(device.matches(protectedVault));
            try { device.open(protectedVault); fail("Fingerprint is authenticated as GCM AAD"); }
            catch (GeneralSecurityException expected) {}
        } finally { if (changed != null) changed.close(); created.session.close(); }
    }

    @Test public void creationAndWriteFailuresPreservePreviousEnvelopeAndAlias() throws Exception {
        Keys keys = new Keys();
        File path = new File(folder.getRoot(), "device.key");
        FailingFile file = new FailingFile(path);
        DeviceVault device = new DeviceVault(file, keys);
        VaultCrypto.Created created = VaultCrypto.create("synthetic".toCharArray());
        try {
            byte[] vault = encrypt(created.session, "synthetic");
            device.enroll(created.session);
            byte[] before = Files.readAllBytes(path.toPath());
            keys.rejectCreate = true;
            try { device.enroll(created.session); fail(); } catch (IOException expected) {}
            keys.rejectCreate = false;
            file.rejectStart = true;
            try { device.enroll(created.session); fail(); } catch (IOException expected) {}
            file.rejectStart = false;
            file.rejectFinish = true;
            try { device.enroll(created.session); fail(); } catch (IllegalStateException expected) {}
            file.rejectFinish = false;
            assertArrayEquals(before, Files.readAllBytes(path.toPath()));
            assertEquals(1, keys.values.size());
            readable(device, vault, "synthetic");
        } finally { created.session.close(); }
    }

    @Test public void obsoleteAliasCleanupFailureDoesNotInvalidateCommittedReplacement() throws Exception {
        Keys keys = new Keys();
        File path = new File(folder.getRoot(), "device.key");
        DeviceVault device = new DeviceVault(new AtomicFile(path), keys);
        VaultCrypto.Created first = VaultCrypto.create("synthetic first".toCharArray());
        VaultCrypto.Created second = VaultCrypto.create("synthetic second".toCharArray());
        try {
            device.enroll(first.session);
            keys.rejectRemove = DeviceVault.ALIAS + ".a";
            device.enroll(second.session);
            assertEquals(2, keys.values.size());
            assertFalse(device.matches(encrypt(first.session, "synthetic first")));
            readable(device, encrypt(second.session, "synthetic second"), "synthetic second");
        } finally { first.session.close(); second.session.close(); }
    }

    @Test public void firstEnrollmentCommitFailureRollsBackAndCanRetry() throws Exception {
        Keys keys = new Keys();
        File path = new File(folder.getRoot(), "device.key");
        FailingFile file = new FailingFile(path);
        DeviceVault device = new DeviceVault(file, keys);
        VaultCrypto.Created created = VaultCrypto.create("synthetic".toCharArray());
        try {
            file.rejectFinish = true;
            try { device.enroll(created.session); fail("An open writer is not a completed commit"); }
            catch (IllegalStateException expected) {}
            assertFalse(device.enabled());
            assertFalse(path.exists());
            assertTrue(keys.values.isEmpty());
            file.rejectFinish = false;
            device.enroll(created.session);
            assertEquals(1, keys.values.size());
            readable(device, encrypt(created.session, "synthetic retry"), "synthetic retry");
        } finally { created.session.close(); }
    }

    @Test public void firstEnrollmentPostCommitFailureKeepsInstalledKey() throws Exception {
        Keys keys = new Keys();
        File path = new File(folder.getRoot(), "device.key");
        FailingFile file = new FailingFile(path);
        DeviceVault device = new DeviceVault(file, keys);
        VaultCrypto.Created created = VaultCrypto.create("synthetic".toCharArray());
        try {
            file.rejectAfterCommit = true;
            device.enroll(created.session);
            assertTrue(device.enabled());
            assertEquals(1, keys.values.size());
            readable(device, encrypt(created.session, "synthetic first commit"), "synthetic first commit");
        } finally { created.session.close(); }
    }

    @Test public void postCommitFileFailurePreservesCommittedReplacement() throws Exception {
        Keys keys = new Keys();
        File path = new File(folder.getRoot(), "device.key");
        FailingFile file = new FailingFile(path);
        DeviceVault device = new DeviceVault(file, keys);
        VaultCrypto.Created first = VaultCrypto.create("synthetic first".toCharArray());
        VaultCrypto.Created second = VaultCrypto.create("synthetic second".toCharArray());
        try {
            device.enroll(first.session);
            file.rejectAfterCommit = true;
            device.enroll(second.session);
            assertEquals(1, keys.values.size());
            readable(device, encrypt(second.session, "synthetic second"), "synthetic second");
        } finally { first.session.close(); second.session.close(); }
    }

    @Test public void disableRemovesEnvelopeAndAllDeviceAliases() throws Exception {
        Keys keys = new Keys();
        File path = new File(folder.getRoot(), "device.key");
        DeviceVault device = new DeviceVault(new AtomicFile(path), keys);
        VaultCrypto.Created created = VaultCrypto.create("synthetic".toCharArray());
        try {
            byte[] vault = encrypt(created.session, "synthetic");
            device.enroll(created.session);
            keys.create(DeviceVault.ALIAS + ".b");
            device.disable();
            assertFalse(device.enabled());
            assertFalse(device.matches(vault));
            assertFalse(path.exists());
            assertTrue(keys.values.isEmpty());
            try { device.open(vault); fail(); } catch (IOException expected) {}
        } finally { created.session.close(); }
    }
}
