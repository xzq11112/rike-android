package app.rike.offline;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;

/** noBackupFilesDir excludes encrypted vault from normal Android backup.
 * AtomicFile preserves last successful ciphertext if the process dies during a write.
 */
public class VaultStore {
    private final AtomicFile file;
    private final Object diskLock;
    public VaultStore(Context context) { File path=new File(context.getNoBackupFilesDir(), "vault.rike");file = new AtomicFile(path);diskLock=VaultAccess.forPath(path.getAbsolutePath()).diskLock; }
    public boolean exists() { return file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists(); }
    public byte[] read() throws IOException { synchronized(diskLock){try (InputStream in = file.openRead()) { return boundedRead(in); }} }
    public void write(byte[] ciphertext) throws IOException {
        synchronized(diskLock){
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(ciphertext); file.finishWrite(out); }
        catch (IOException error) { if (out != null) file.failWrite(out); throw error; }
        }
    }
    public static byte[] boundedRead(InputStream in) throws IOException {
        if (in == null) throw new IOException("无法打开文件");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) {
            if (out.size() + n > VaultCrypto.MAX_FILE) throw new IOException("文件超过上限");
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
}
