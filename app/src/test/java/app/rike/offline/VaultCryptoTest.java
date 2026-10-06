package app.rike.offline;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.security.GeneralSecurityException;

public class VaultCryptoTest {
    private static final String PASSWORD="synthetic-test-password-only";
    private interface Attempt { void run() throws Exception; }
    private static void rejected(Attempt action)throws Exception{
        try{action.run();fail("Expected rejection");}catch(GeneralSecurityException|IllegalArgumentException|IllegalStateException expected){}
    }
    @Test public void passwordRecoveryAndPasswordChangeRoundTrip()throws Exception{
        VaultCrypto.Created c=VaultCrypto.create(PASSWORD.toCharArray());
        byte[] plain="测试梦境和私人草稿".getBytes(StandardCharsets.UTF_8),file=VaultCrypto.encrypt(c.session,plain);
        VaultCrypto.Opened p=VaultCrypto.open(file,PASSWORD.toCharArray()),r=VaultCrypto.recover(file,c.recoveryCode);
        assertArrayEquals(plain,p.plaintext);assertArrayEquals(plain,r.plaintext);p.session.close();r.session.close();
        VaultCrypto.Session changed=VaultCrypto.changePassword(c.session,"another-synthetic-password".toCharArray());
        byte[] next=VaultCrypto.encrypt(changed,plain);
        rejected(()->VaultCrypto.open(next,PASSWORD.toCharArray()));
        VaultCrypto.Opened changedOpen=VaultCrypto.open(next,"another-synthetic-password".toCharArray());
        VaultCrypto.Opened recovered=VaultCrypto.recover(next,c.recoveryCode);
        assertArrayEquals(plain,changedOpen.plaintext);assertArrayEquals(plain,recovered.plaintext);
        changedOpen.session.close();recovered.session.close();changed.close();c.session.close();
    }
    @Test public void wrongSecretsTamperingTruncationAndClosedKeyRejected()throws Exception{
        VaultCrypto.Created c=VaultCrypto.create(PASSWORD.toCharArray());
        byte[] file=VaultCrypto.encrypt(c.session,"secret".getBytes(StandardCharsets.UTF_8));
        rejected(()->VaultCrypto.open(file,"incorrect".toCharArray()));
        rejected(()->VaultCrypto.recover(file,"0".repeat(64)));
        for(int offset:new int[]{0,9,30,99,145,file.length-1}){
            byte[] corrupt=file.clone();corrupt[offset]^=1;rejected(()->VaultCrypto.recover(corrupt,c.recoveryCode));
        }
        rejected(()->VaultCrypto.open(Arrays.copyOf(file,50),PASSWORD.toCharArray()));
        c.session.close();assertTrue(c.session.isClosed());rejected(()->VaultCrypto.encrypt(c.session,new byte[1]));
    }
    @Test public void freshNoncesPaddingAndLimits()throws Exception{
        VaultCrypto.Created c=VaultCrypto.create(PASSWORD.toCharArray());byte[] plain={1,2,3};
        byte[] a=VaultCrypto.encrypt(c.session,plain),b=VaultCrypto.encrypt(c.session,plain);
        assertFalse(Arrays.equals(a,b));assertEquals(a.length,VaultCrypto.encrypt(c.session,new byte[100]).length);
        rejected(()->VaultCrypto.encrypt(c.session,new byte[VaultCrypto.MAX_PLAINTEXT+1]));
        rejected(()->VaultCrypto.create(new char[0]));
        rejected(()->VaultCrypto.create("a".repeat(1025).toCharArray()));c.session.close();
    }
    @Test public void sixDigitPasswordKeepsLeadingZeroAndRewrapsOldVault()throws Exception {
        char[] pin="012345".toCharArray();VaultCrypto.Created c=VaultCrypto.create(PASSWORD.toCharArray());
        byte[] original="synthetic-preserved-record".getBytes(StandardCharsets.UTF_8);
        VaultCrypto.Session changed=VaultCrypto.changePassword(c.session,pin);
        byte[] file=VaultCrypto.encrypt(changed,original);
        VaultCrypto.Opened opened=VaultCrypto.open(file,pin);assertArrayEquals(original,opened.plaintext);
        rejected(()->VaultCrypto.open(file,"12345".toCharArray()));
        rejected(()->VaultCrypto.open(file,PASSWORD.toCharArray()));
        VaultCrypto.Opened recovered=VaultCrypto.recover(file,c.recoveryCode);assertArrayEquals(original,recovered.plaintext);
        opened.session.close();recovered.session.close();changed.close();c.session.close();
        VaultCrypto.requirePassword("abcdefghijkl".toCharArray());
        for(String accepted:new String[]{"a","中文","12345","1234567","12a456","１２３４５６","abcdefghijk"})
            VaultCrypto.requirePassword(accepted.toCharArray());
    }
    @Test public void shortAndChineseSecretsRoundTripAndRekeyPreservesData()throws Exception {
        byte[] plain="SYNTHETIC-UNCHANGED-DATA".getBytes(StandardCharsets.UTF_8);
        for(String secret:new String[]{"a","春天 蓝鲸"}){
            VaultCrypto.Created c=VaultCrypto.create(secret.toCharArray());
            byte[] file=VaultCrypto.encrypt(c.session,plain);
            VaultCrypto.Opened opened=VaultCrypto.open(file,secret.toCharArray());
            assertArrayEquals(plain,opened.plaintext);opened.session.close();c.session.close();
        }
        VaultCrypto.Created old=VaultCrypto.create(PASSWORD.toCharArray());
        rejected(()->VaultCrypto.changePassword(old.session,new char[0]));
        VaultCrypto.Session changed=VaultCrypto.changePassword(old.session,"猫".toCharArray());
        byte[] file=VaultCrypto.encrypt(changed,plain);
        VaultCrypto.Opened opened=VaultCrypto.open(file,"猫".toCharArray());
        VaultCrypto.Opened recovered=VaultCrypto.recover(file,old.recoveryCode);
        assertArrayEquals(plain,opened.plaintext);assertArrayEquals(plain,recovered.plaintext);
        rejected(()->VaultCrypto.open(file,PASSWORD.toCharArray()));
        opened.session.close();recovered.session.close();changed.close();old.session.close();
    }
    @org.junit.Test public void biometricKeyAuthenticatesVaultAndRejectsWrongKey() throws Exception {
        VaultCrypto.Created c=VaultCrypto.create("synthetic-test-password".toCharArray());
        byte[] plain="synthetic biometric record".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] file=VaultCrypto.encrypt(c.session,plain),key=VaultCrypto.biometricKey(c.session);
        VaultCrypto.Opened opened=VaultCrypto.openWithBiometricKey(file,key);
        org.junit.Assert.assertArrayEquals(plain,opened.plaintext);opened.session.close();
        key[0]^=1;
        try{VaultCrypto.openWithBiometricKey(file,key);org.junit.Assert.fail("wrong key accepted");}
        catch(java.security.GeneralSecurityException expected){}
        c.session.close();
        try{VaultCrypto.biometricKey(c.session);org.junit.Assert.fail("closed key exported");}
        catch(IllegalStateException expected){}
        java.util.Arrays.fill(key,(byte)0);
    }
}

