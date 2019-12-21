package com.ilivoo.hotswap;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

class IOUtils {
    private IOUtils() {
    }

    public static void safelyClose(Closeable closeable) {
        if (closeable != null) {
            if (closeable instanceof Flushable) {
                try {
                    ((Flushable) closeable).flush();
                } catch (IOException ex) {
                }
            }
            try {
                closeable.close();
            } catch (IOException ex) {
            }
        }
    }

    public static List<String> walkFileRecursively(File root, final String suffix) {
        final List<String> result = new ArrayList<String>();
        if (root != null && root.exists()) {
            if (root.isDirectory()) {
                root.listFiles(new FileFilter() {
                    public boolean accept(File file) {
                        if (file.isDirectory()) {
                            result.addAll(walkFileRecursively(file, suffix));
                        } else if (file.getName().endsWith(suffix)) {
                            result.add(file.getPath());
                        }
                        return false;
                    }
                });
            } else if (root.getName().endsWith(suffix)) {
                result.add(root.getPath());
            }
        }
        return result;
    }

    public static List<String> walkDirRecursively(File root, String parent) {
        if (parent == null) parent = "";
        List<String> result = new ArrayList<String>();
        if (root != null && root.exists()) {
            if (root.isDirectory()) {
                final List<String> childrenDirName = new ArrayList<String>();
                File[] childrenDir = root.listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        boolean isDirectory = new File(dir, name).isDirectory();
                        if (isDirectory) {
                            childrenDirName.add(name);
                        }
                        return isDirectory;
                    }
                });
                if (childrenDir != null) {
                    for (int i = 0; i < childrenDir.length; i++) {
                        String newParent = parent + "/" + childrenDirName.get(i);
                        String finalParent = parent.length() == 0 ? newParent.substring(1) : newParent;
                        result.add(finalParent);
                        result.addAll(walkDirRecursively(childrenDir[i], finalParent));
                    }
                }
            }
        }
        return result;
    }

    public static void deleteRecursively(File root, boolean deleteRoot, boolean deleteDir) {
        if (root != null && root.exists()) {
            if (root.isDirectory()) {
                File[] children = root.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursively(child, true, deleteDir);
                    }
                }
            }
            if (deleteRoot && (root.isFile() || (root.isDirectory() && deleteDir))) {
                root.delete();
            }
        }
    }

    public static byte[] copyToByteArray(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
        out.flush();
        safelyClose(in);
        return out.toByteArray();
    }
}
