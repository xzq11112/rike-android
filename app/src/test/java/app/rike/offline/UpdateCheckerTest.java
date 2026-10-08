package app.rike.offline;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateCheckerTest {
    static JSONObject release(String version)throws Exception {
        String file="rike-"+version+"-release.apk";
        return new JSONObject().put("tag_name","v"+version).put("draft",false).put("prerelease",false)
            .put("assets",new JSONArray().put(new JSONObject().put("name",file).put("state","uploaded").put("size",123)
                .put("browser_download_url","https://github.com/xzq11112/rike-android/releases/download/v"+version+"/"+file)));
    }
    @Test public void comparesNumbersAndDoesNotOfferDowngrades()throws Exception {
        assertTrue(UpdateChecker.compareVersions("0.6.10","0.6.9")>0);
        assertTrue(UpdateChecker.compareVersions("1.0.0","0.99.99")>0);
        assertEquals(0,UpdateChecker.compareVersions("0.6.6","0.6.6-test"));
        assertTrue(UpdateChecker.parse(release("0.6.5").toString()).message("0.6.6").contains("高于"));
        assertTrue(UpdateChecker.parse(release("0.6.6").toString()).message("0.6.6").contains("已是最新"));
        assertTrue(UpdateChecker.parse(release("0.6.7").toString()).message("0.6.6").contains("发现新版本"));
    }
    @Test public void rejectsDraftPrereleaseMalformedAndUnrecognisedVersions()throws Exception {
        for(JSONObject json:new JSONObject[]{release("0.6.7").put("draft",true),release("0.6.7").put("prerelease",true),
            release("0.6.7-beta"),release("0.6.7").put("tag_name","v999999999999999999.1.1"),release("0.6.7").put("tag_name","v0.06.7"),new JSONObject()})
            assertThrows(UpdateChecker.CheckException.class,()->UpdateChecker.parse(json.toString()));
        assertThrows(UpdateChecker.CheckException.class,()->UpdateChecker.parse("<html>bad gateway</html>"));
    }
    @Test public void onlyAcceptsExactPublishedApkInOfficialRepository()throws Exception {
        assertTrue(UpdateChecker.parse(release("0.6.7").toString()).apkUrl.endsWith("rike-0.6.7-release.apk"));
        for(String url:new String[]{"https://evil.example/app.apk","https://github.com.evil.example/app.apk",
            "http://github.com/xzq11112/rike-android/releases/download/v0.6.7/rike-0.6.7-release.apk",
            "https://github.com/other/repo/releases/download/v0.6.7/rike-0.6.7-release.apk"}) {
            JSONObject json=release("0.6.7");json.getJSONArray("assets").getJSONObject(0).put("browser_download_url",url);
            assertNull(UpdateChecker.parse(json.toString()).apkUrl);
        }
        JSONObject unsigned=release("0.6.7");unsigned.getJSONArray("assets").getJSONObject(0).put("name","app-release-unsigned.apk");
        assertNull(UpdateChecker.parse(unsigned.toString()).apkUrl);
        JSONObject pending=release("0.6.7");pending.getJSONArray("assets").getJSONObject(0).put("state","starter");
        assertNull(UpdateChecker.parse(pending.toString()).apkUrl);
        assertTrue(UpdateChecker.parse(release("0.6.7").put("assets",new JSONArray()).toString()).message("0.6.6").contains("暂未提供"));
    }
    static final class Connection extends HttpsURLConnection {
        final int status;final byte[] body;boolean disconnected,read;
        Connection(int status,byte[] body)throws Exception{super(new URL(UpdateChecker.API));this.status=status;this.body=body;}
        public void connect(){} public void disconnect(){disconnected=true;} public boolean usingProxy(){return false;}
        public String getCipherSuite(){return "synthetic";} public Certificate[] getLocalCertificates(){return null;}
        public Certificate[] getServerCertificates(){return null;}
        public int getResponseCode(){return status;}
        public InputStream getInputStream(){read=true;return new ByteArrayInputStream(body);}
        public long getContentLengthLong(){return -1;}
    }
    @Test public void requestHasBoundedTimeoutsNoRedirectsAndNoUpload()throws Exception {
        Connection c=new Connection(200,release("0.6.7").toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("0.6.7",new UpdateChecker.Request(()->c).load().version);
        assertEquals("GET",c.getRequestMethod());assertFalse(c.getDoOutput());assertFalse(c.getInstanceFollowRedirects());
        assertEquals(8000,c.getConnectTimeout());assertEquals(10000,c.getReadTimeout());assertFalse(c.getUseCaches());
        assertEquals(3,c.getRequestProperties().size());assertNull(c.getRequestProperty("Authorization"));
        assertNull(c.getURL().getQuery());assertTrue(c.disconnected);
    }
    @Test public void httpErrorsAndOversizedResponsesAreRecoverableAndCloseConnection()throws Exception {
        for(int status:new int[]{301,302,403,404,429,500}) {
            Connection c=new Connection(status,new byte[0]);
            assertThrows(UpdateChecker.CheckException.class,()->new UpdateChecker.Request(()->c).load());
            assertFalse(c.read);assertTrue(c.disconnected);
        }
        Connection huge=new Connection(200,new byte[UpdateChecker.MAX_RESPONSE_BYTES+1]);
        assertThrows(UpdateChecker.CheckException.class,()->new UpdateChecker.Request(()->huge).load());assertTrue(huge.disconnected);
        Connection cancelled=new Connection(200,new byte[0]);UpdateChecker.Request request=new UpdateChecker.Request(()->cancelled);
        request.cancel();assertThrows(InterruptedIOException.class,request::load);assertFalse(cancelled.read);assertTrue(cancelled.disconnected);
    }
}
