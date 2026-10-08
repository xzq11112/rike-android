package app.rike.offline;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Fetches public release metadata only. No context, vault, credentials or device identifiers. */
final class UpdateChecker {
    static final String LATEST_PAGE = "https://github.com/xzq11112/rike-android/releases/latest";
    static final String API = "https://api.github.com/repos/xzq11112/rike-android/releases/latest";
    static final int MAX_RESPONSE_BYTES = 512 * 1024;

    static final class Release {
        final String version, apkUrl;
        Release(String version, String apkUrl) { this.version=version; this.apkUrl=apkUrl; }
        String message(String installed) {
            int comparison=compareVersions(version, installed);
            String status=comparison>0?"发现新版本 v"+version:comparison==0
                ?(installed.endsWith("-test")?"测试版对应版本号与最新正式版一致 v":"已是最新正式版本 v")+version
                :"当前版本高于最新正式版 v"+version;
            return apkUrl==null?status+"\n该版本暂未提供正式 APK，请前往 GitHub 查看。":status;
        }
    }
    static final class CheckException extends IOException {
        CheckException(String message) { super(message); }
    }
    interface ConnectionFactory { HttpsURLConnection open() throws IOException; }

    static class Request {
        private final ConnectionFactory factory;
        private HttpsURLConnection connection;
        private boolean cancelled;
        Request() { this(()->(HttpsURLConnection)new URL(API).openConnection()); }
        Request(ConnectionFactory factory) { this.factory=factory; }
        synchronized void cancel() {
            cancelled=true;
            if(connection!=null)connection.disconnect();
        }
        Release load() throws IOException {
            HttpsURLConnection current=factory.open();
            synchronized(this) {
                if(cancelled){current.disconnect();throw new InterruptedIOException();}
                connection=current;
            }
            try {
                current.setConnectTimeout(8000);
                current.setReadTimeout(10000);
                current.setInstanceFollowRedirects(false);
                current.setUseCaches(false);
                current.setRequestMethod("GET");
                current.setRequestProperty("Accept","application/vnd.github+json");
                current.setRequestProperty("User-Agent","Rike-Update-Check");
                current.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
                int status=current.getResponseCode();
                if(status==403||status==429)throw new CheckException("GitHub 暂时限制了检查请求，请稍后重试，或打开发布页查看。");
                if(status==404)throw new CheckException("暂未找到正式发布版本，请前往 GitHub 查看。");
                if(status!=200)throw new CheckException("暂时无法连接 GitHub，请稍后重试，或打开发布页查看。");
                if(current.getContentLengthLong()>MAX_RESPONSE_BYTES)throw new CheckException("更新信息无法识别，请前往 GitHub 查看。");
                try(InputStream in=current.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] bytes=new byte[8192];int count;
                    while((count=in.read(bytes))!=-1) {
                        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                        if(out.size()+count>MAX_RESPONSE_BYTES)throw new CheckException("更新信息无法识别，请前往 GitHub 查看。");
                        out.write(bytes,0,count);
                    }
                    return parse(out.toString(StandardCharsets.UTF_8.name()));
                }
            } finally {
                current.disconnect();
                synchronized(this){if(connection==current)connection=null;}
            }
        }
    }

    static Release parse(String json) throws CheckException {
        try {
            JSONObject release=new JSONObject(json);
            if(release.getBoolean("draft")||release.getBoolean("prerelease"))throw new IllegalArgumentException();
            String tag=release.getString("tag_name");
            String version=tag.startsWith("v")?tag.substring(1):tag;
            parts(version);
            String file="rike-"+version+"-release.apk";
            String expected="https://github.com/xzq11112/rike-android/releases/download/"+tag+"/"+file;
            JSONArray assets=release.getJSONArray("assets");
            String apk=null;
            for(int i=0;i<assets.length();i++) {
                JSONObject asset=assets.getJSONObject(i);
                if(file.equals(asset.optString("name"))&&"uploaded".equals(asset.optString("state"))
                    &&asset.optLong("size")>0&&expected.equals(asset.optString("browser_download_url")))apk=expected;
            }
            return new Release(version,apk);
        } catch(JSONException|IllegalArgumentException e) {
            throw new CheckException("更新信息无法识别，请前往 GitHub 查看。");
        }
    }
    static int compareVersions(String latest,String installed) {
        // The debug build adds this suffix; it is not a production APK and is labelled separately in UI.
        if(installed.endsWith("-test"))installed=installed.substring(0,installed.length()-5);
        int[] a=parts(latest),b=parts(installed);
        for(int i=0;i<3;i++){int comparison=Integer.compare(a[i],b[i]);if(comparison!=0)return comparison;}
        return 0;
    }
    private static int[] parts(String version) {
        if(!version.matches("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})"))throw new IllegalArgumentException();
        String[] parts=version.split("\\.");
        return new int[]{Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2])};
    }
}
