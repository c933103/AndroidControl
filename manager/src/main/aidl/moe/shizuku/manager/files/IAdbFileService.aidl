package moe.shizuku.manager.files;

import android.os.ParcelFileDescriptor;

interface IAdbFileService {
    void destroy() = 16777114;

    String[] listDirectory(String path) = 1;
    String stat(String path) = 2;
    ParcelFileDescriptor openRead(String path) = 3;
}
