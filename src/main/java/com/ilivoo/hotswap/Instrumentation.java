package com.ilivoo.hotswap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

//VM args: -javaagent:target/hotswap-1.0-SNAPSHOT.jar=name=value,name=value
public class Instrumentation {
    private static java.lang.instrument.Instrumentation INSTRUMENTATION;
    private static boolean TRY_DYNAMIC_ATTACH = !Boolean
            .getBoolean("ilivoo.hotSwap.skipDynamicAttach");

    Instrumentation() {
    }

    private static void usage() {
        System.out.println("Usage: java -jar hotswap.jar pid param");
        System.exit(0);
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("params must two");
            usage();
        }
        try {
            attach(args[0], getAgentJar(), args[1]);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            usage();
        }
        System.out.printf("process %s attach success\n", args[0]);
    }

    public static File getAgentJar() throws Exception {
        File result;
        Class<?> clz = Instrumentation.class;
        String classDesc = "/" + clz.getName().replace('.', '/') + ".class";
        URL url = clz.getResource(classDesc);
        String scheme = url.toURI().getScheme();
        if (scheme.equals("jar")) {
            String jarFilePath = clz.getProtectionDomain().getCodeSource().getLocation().getFile();
            jarFilePath = URLDecoder.decode(jarFilePath, "UTF-8");
            result = new File(jarFilePath);
        } else {
            //may be need add extra class
            String path = StringUtils.cleanPath(url.getPath());
            String classPath = path.substring(0, path.length() - classDesc.length());
            List<String> entryList = new ArrayList<String>();
            List<String> classList = IOUtils.walkFileRecursively(new File(classPath), ".class");
            for (String clazz : classList) {
                clazz = StringUtils.cleanPath(clazz);
                entryList.add(clazz.replaceFirst(classPath + "/", ""));
            }
            result = File.createTempFile("hotSwapAgent", ".jar");
            saveAgentJar(result, entryList, Instrumentation.class);
        }
        return result;
    }

    public static void agentmain(String agentArgs, java.lang.instrument.Instrumentation inst) {
        INSTRUMENTATION = inst;
        HotSwapper.instance().parseProperty(agentArgs).start();
    }

    public static void premain(String agentArgs, java.lang.instrument.Instrumentation inst) {
        INSTRUMENTATION = inst;
        HotSwapper.instance().parseProperty(agentArgs).start();
    }

    public static boolean supportInstrument() {
        try {
            return instance() != null;
        } catch (Throwable t) {
        }
        return false;
    }

    public static java.lang.instrument.Instrumentation instance() {
        if (INSTRUMENTATION != null) {
            return INSTRUMENTATION;
        } else if (TRY_DYNAMIC_ATTACH) {
            try {
                tryDynamicAttach();
                return INSTRUMENTATION;
            } catch (Exception e) {
                throw new RuntimeException("Dynamic Attach failed. You may add this JAR " +
                        "as -javaagent manually, or supply -Djdk.attach.allowAttachSelf");
            }
        } else {
            throw new RuntimeException("No instrumentation. Add this JAR as -javaagent manually.");
        }
    }

    private static void attach(String pid, File agentFile, String agentArs) throws Exception {
        String vmName = "com.sun.tools.attach.VirtualMachine";

        Class vmClass;
        try {
            vmClass = ClassLoader.getSystemClassLoader().loadClass(vmName);
        } catch (Exception var16) {
            String javaHome = StringUtils.cleanPath(System.getProperty("java.home"));
            File toolsFile = new File(javaHome + "/../lib/tools.jar");
            if (!toolsFile.exists() || !toolsFile.isFile()) {
                toolsFile = new File(javaHome + "/lib/tools.jar");
            }
            if (!toolsFile.exists() || !toolsFile.isFile()) {
                System.out.printf("no tools.jar find in [%s].\n", StringUtils.cleanPath(toolsFile.getPath()));
                System.exit(0);
            }
            URL url = toolsFile.toURI().toURL();
            ClassLoader classLoader = URLClassLoader.newInstance(new URL[]{url});
            vmClass = classLoader.loadClass(vmName);
        }
        Object vm = vmClass.getDeclaredMethod("attach", String.class).invoke(null, pid);

        try {
            Method loadAgentMethod = vmClass.getDeclaredMethod("loadAgent", String.class, String.class);
            loadAgentMethod.invoke(vm, agentFile.getAbsolutePath(), agentArs);
        } finally {
            vmClass.getDeclaredMethod("detach").invoke(vm);
        }
    }

    private static void tryDynamicAttach() throws Exception {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        String pid = runtimeName.substring(0, runtimeName.indexOf('@'));

        File agentFile = File.createTempFile("hotSwapAgent", ".jar");
        try {
            String classDesc = Installer.class.getName().replace('.', '/') + ".class";
            List<String> entryList = new ArrayList<String>();
            entryList.add(classDesc);
            saveAgentJar(agentFile, entryList, Installer.class);
            attach(pid, agentFile, "");
            Field field = ClassLoader.getSystemClassLoader()
                    .loadClass(Instrumentation.Installer.class.getName())
                    .getDeclaredField("INSTRUMENTATION");
            INSTRUMENTATION = (java.lang.instrument.Instrumentation) field.get(null);
        } finally {
            agentFile.delete();
        }
    }

    private static void saveAgentJar(File agentFile, List<String> entryList, Class<?> instrumentClz) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Name("Agent-Class"), instrumentClz.getName());
        manifest.getMainAttributes().put(new Name("Can-Redefine-Classes"), "true");
        JarOutputStream jos = new JarOutputStream(new FileOutputStream(agentFile), manifest);

        try {
            for (String classDesc : entryList) {
                InputStream is = Instrumentation.class.getResourceAsStream('/' + classDesc);
                try {
                    jos.putNextEntry(new JarEntry(classDesc));
                    byte[] buffer = new byte[1024];

                    int index;
                    while ((index = is.read(buffer)) != -1) {
                        jos.write(buffer, 0, index);
                    }
                    jos.closeEntry();
                } finally {
                    IOUtils.safelyClose(is);
                }
            }
        } finally {
            IOUtils.safelyClose(jos);
        }
    }

    private static class Installer {
        public static volatile java.lang.instrument.Instrumentation INSTRUMENTATION;

        private Installer() {
        }

        public static void agentmain(String agentArgs, java.lang.instrument.Instrumentation inst) {
            INSTRUMENTATION = inst;
        }
    }
}
