package app.rike.offline;

import android.content.Context;
import android.security.keystore.*;
import android.util.AtomicFile;
import java.io.*;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Candidate configuration is installed only after authentication and atomic write.
 * Legacy 60-byte envelopes remain readable; v2 adds one alias-slot byte. */
final class BiometricVault {
    static final String ALIAS="rike.biometric.v1";
    interface Keys { SecretKey create(String alias)throws Exception;SecretKey get(String alias)throws Exception;void remove(String alias)throws Exception; }
    private final AtomicFile file;
    private final Keys keys;
    private String pendingAlias;
    private Cipher pendingCipher;
    BiometricVault(Context context){this(new AtomicFile(new File(context.getNoBackupFilesDir(),"biometric.key")),new Keys(){
        private KeyStore store()throws Exception{KeyStore k=KeyStore.getInstance("AndroidKeyStore");k.load(null);return k;}
        public SecretKey get(String alias)throws Exception{return (SecretKey)store().getKey(alias,null);}
        public void remove(String alias)throws Exception{store().deleteEntry(alias);}
        public SecretKey create(String alias)throws Exception{
            KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(-1).setInvalidatedByBiometricEnrollment(true).build());
            SecretKey key=g.generateKey();KeyInfo info=(KeyInfo)SecretKeyFactory.getInstance("AES","AndroidKeyStore").getKeySpec(key,KeyInfo.class);
            if(!info.isInsideSecureHardware()){remove(alias);throw new IllegalStateException("Hardware key unavailable");}return key;
        }
    });}
    BiometricVault(AtomicFile file,Keys keys){this.file=file;this.keys=keys;}
    boolean enabled(){return file.getBaseFile().exists()||new File(file.getBaseFile()+".bak").exists();}
    synchronized void cancelEnrollment()throws Exception{
        String candidate=pendingAlias;pendingAlias=null;pendingCipher=null;if(candidate!=null)keys.remove(candidate);
    }
    synchronized void disable()throws Exception{cancelEnrollment();file.delete();for(String alias:new String[]{ALIAS,ALIAS+".a",ALIAS+".b"})keys.remove(alias);}
    static String alias(byte[] envelope)throws IOException{
        if(envelope.length==60)return ALIAS;
        if(envelope.length==61&&(envelope[0]==1||envelope[0]==2))return ALIAS+(envelope[0]==1?".a":".b");
        throw new IOException("Invalid biometric envelope");
    }
    private byte[] envelope()throws Exception{try(InputStream in=file.openRead()){byte[] b=VaultStore.boundedRead(in);alias(b);return b;}}
    synchronized Cipher enrollmentCipher()throws Exception{
        cancelEnrollment();String active=enabled()?alias(envelope()):null;
        pendingAlias=ALIAS+((ALIAS+".a").equals(active)?".b":".a");
        try{SecretKey key=keys.create(pendingAlias);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);pendingCipher=c;return c;}
        catch(Exception e){cancelEnrollment();throw e;}
    }
    synchronized void enroll(Cipher cipher,VaultCrypto.Session session)throws Exception{
        if(cipher!=pendingCipher||pendingAlias==null)throw new IllegalStateException("Enrollment cancelled");
        byte[] key=VaultCrypto.biometricKey(session);FileOutputStream out=null;String old=enabled()?alias(envelope()):null;
        try{
            byte[] encrypted=cipher.doFinal(key);out=file.startWrite();out.write(pendingAlias.endsWith(".a")?1:2);out.write(cipher.getIV());out.write(encrypted);file.finishWrite(out);
        }catch(Exception e){if(out!=null)file.failWrite(out);cancelEnrollment();throw e;}
        finally{Arrays.fill(key,(byte)0);}
        String installed=pendingAlias;pendingAlias=null;pendingCipher=null;
        // Successful atomic installation is the commit point. Cleanup cannot turn
        // it into a reported enrollment failure; stale wrapped keys cannot unlock.
        if(old!=null&&!old.equals(installed))try{keys.remove(old);}catch(Exception ignored){}
    }
    Cipher unlockCipher()throws Exception{
        byte[] b=envelope();int offset=b.length==60?0:1;Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,keys.get(alias(b)),new GCMParameterSpec(128,b,offset,12));return c;
    }
    VaultCrypto.Opened open(Cipher cipher,byte[] vault)throws Exception{
        byte[] b=envelope();int offset=b.length==60?0:1;byte[] key=cipher.doFinal(b,offset+12,48);
        try{return VaultCrypto.openWithBiometricKey(vault,key);}finally{Arrays.fill(key,(byte)0);}
    }
}
