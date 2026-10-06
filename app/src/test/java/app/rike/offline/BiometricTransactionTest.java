package app.rike.offline;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import javax.crypto.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Transaction and legacy format tests with synthetic JCA keys, not hardware authentication. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class BiometricTransactionTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private static class Keys implements BiometricVault.Keys {
        Map<String,SecretKey> values=new HashMap<>();boolean reject;
        public SecretKey create(String alias)throws Exception{if(reject)throw new IOException();KeyGenerator g=KeyGenerator.getInstance("AES");g.init(256);SecretKey key=g.generateKey();values.put(alias,key);return key;}
        public SecretKey get(String alias){return values.get(alias);}
        public void remove(String alias){values.remove(alias);}
    }
    private static class FailingFile extends AtomicFile {
        boolean fail;FailingFile(File file){super(file);}
        @Override public FileOutputStream startWrite()throws IOException{if(fail)throw new IOException();return super.startWrite();}
    }
    private static void readable(BiometricVault bio,VaultCrypto.Created key)throws Exception{
        byte[] file=VaultCrypto.encrypt(key.session,"synthetic".getBytes(StandardCharsets.UTF_8));VaultCrypto.Opened opened=bio.open(bio.unlockCipher(),file);assertEquals("synthetic",new String(opened.plaintext,StandardCharsets.UTF_8));Arrays.fill(opened.plaintext,(byte)0);opened.session.close();
    }
    @Test public void cancelledReEnrollmentPreservesPreviousWorkingConfiguration()throws Exception{
        Keys keys=new Keys();File path=new File(folder.getRoot(),"biometric.key");BiometricVault bio=new BiometricVault(new AtomicFile(path),keys);VaultCrypto.Created key=VaultCrypto.create("synthetic".toCharArray());
        try{bio.enroll(bio.enrollmentCipher(),key.session);byte[] before=Files.readAllBytes(path.toPath());Cipher candidate=bio.enrollmentCipher();bio.cancelEnrollment();assertArrayEquals(before,Files.readAllBytes(path.toPath()));assertTrue(bio.enabled());readable(bio,key);try{bio.enroll(candidate,key.session);fail();}catch(IllegalStateException expected){}assertEquals(1,keys.values.size());}finally{key.session.close();}
    }
    @Test public void failedAuthenticationPreparationAndWriteKeepOldEnvelopeAndKey()throws Exception{
        Keys keys=new Keys();File path=new File(folder.getRoot(),"biometric.key");FailingFile file=new FailingFile(path);BiometricVault bio=new BiometricVault(file,keys);VaultCrypto.Created key=VaultCrypto.create("synthetic".toCharArray());
        try{bio.enroll(bio.enrollmentCipher(),key.session);byte[] before=Files.readAllBytes(path.toPath());keys.reject=true;try{bio.enrollmentCipher();fail();}catch(IOException expected){}keys.reject=false;readable(bio,key);Cipher candidate=bio.enrollmentCipher();file.fail=true;try{bio.enroll(candidate,key.session);fail();}catch(IOException expected){}file.fail=false;assertArrayEquals(before,Files.readAllBytes(path.toPath()));readable(bio,key);assertEquals(1,keys.values.size());}finally{key.session.close();}
    }
    @Test public void legacyEnvelopeCanUnlockAndUpgradeWithoutChangingVaultKey()throws Exception{
        Keys keys=new Keys();File path=new File(folder.getRoot(),"biometric.key");SecretKey secret=keys.create(BiometricVault.ALIAS);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret);VaultCrypto.Created key=VaultCrypto.create("synthetic".toCharArray());byte[] raw=VaultCrypto.biometricKey(key.session);
        try{byte[] encrypted=cipher.doFinal(raw);try(OutputStream out=new FileOutputStream(path)){out.write(cipher.getIV());out.write(encrypted);}BiometricVault bio=new BiometricVault(new AtomicFile(path),keys);readable(bio,key);bio.enroll(bio.enrollmentCipher(),key.session);assertEquals(61,Files.readAllBytes(path.toPath()).length);readable(bio,key);assertFalse(keys.values.containsKey(BiometricVault.ALIAS));bio.disable();assertFalse(bio.enabled());assertTrue(keys.values.isEmpty());}finally{Arrays.fill(raw,(byte)0);key.session.close();}
    }
}
