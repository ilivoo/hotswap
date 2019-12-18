package com.ilivoo.hotswap;

import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * hot resource represent a hot swapper basic unit.
 */
public final class HotResource {

    /**
     * reload path
     */
    private final String path;

    /**
     * reload path name
     */
    private final String name;

    /**
     * reload class name
     */
    private final String className;

    /**
     * last modify time
     */
    private final long lastModifyTime;

    /**
     * class bytes md5, use to compare
     */
    private byte[] md5;

    /**
     * last hot swapper time
     */
    private long lastSwapTime;

    /**
     * resource bytes cache
     */
    private WeakReference<byte[]> resourceBytesRef;

    public HotResource(String path, String name) throws Exception {
        this.path = path;
        this.name = name;
        File file = new File(path, name);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException(
                    String.format("%s/%s is not exist or not a file !", path, name));
        }
        this.lastModifyTime = file.lastModified();
        byte[] classBytes = null;
        ClassReader classReader = null;
        try {
            classBytes = IOUtils.copyToByteArray(file.toURI().toURL().openStream());
            classReader = new ClassReader(classBytes);
            resourceBytesRef = new WeakReference<byte[]>(classBytes);
        } catch (IOException ex) {
            throw new RuntimeException(
                    String.format("can not parse %s/%s file !", path, name), ex);
        }
        className = classReader.getClassName().replace('/', '.');
        md5 = null;
        if (HotSwapper.instance().isMd5compare()) {
            md5 = MessageDigest.getInstance("MD5").digest(classBytes);
        }
    }

    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    public String getClassName() {
        return className;
    }

    public long getLastModifyTime() {
        return lastModifyTime;
    }

    public byte[] getMd5() {
        return md5 != null ? md5.clone() : md5;
    }

    public long getLastSwapTime() {
        return lastSwapTime;
    }

    public void setLastSwapTime(long lastSwapTime) {
        this.lastSwapTime = lastSwapTime;
    }

    public byte[] getBytes() throws IOException {
        byte[] result = resourceBytesRef.get();
        if (result != null) {
            return result;
        }
        URL url = null;
        try {
            url = new File(path, name).toURI().toURL();
        } catch (MalformedURLException e) {
        }
        result = IOUtils.copyToByteArray(url.openStream());
        resourceBytesRef = new WeakReference<byte[]>(result);
        return result;
    }

    public void deleteResource() {
        File file = new File(path, name);
        if (file.exists()) {
            file.delete();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        HotResource that = (HotResource) o;

        if (!path.equals(that.path)) return false;
        if (!name.equals(that.name)) return false;
        if (!className.equals(that.className)) return false;
        return HotSwapper.instance().isMd5compare()
                ? Arrays.equals(md5, that.md5) : lastModifyTime == that.lastModifyTime;
    }

    @Override
    public int hashCode() {
        int result = path.hashCode();
        result = 31 * result + name.hashCode();
        result = 31 * result + className.hashCode();
        result = 31 * result + (HotSwapper.instance().isMd5compare()
                ? Arrays.hashCode(md5) : (int) (lastModifyTime ^ (lastModifyTime >>> 32)));
        return result;
    }

    @Override
    public String toString() {
        return "HotResource{" +
                "path='" + path + '\'' +
                ", name='" + name + '\'' +
                ", className='" + className + '\'' +
                ", lastModifyTime=" + lastModifyTime +
                ", lastSwapTime=" + lastSwapTime +
                '}';
    }
}
