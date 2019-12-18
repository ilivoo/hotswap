package com.ilivoo.hotswap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

//VM args: -javaagent:target/hotswap-1.0-SNAPSHOT.jar=name=value,name=value
public class InstrumentationSupport {
    private static Instrumentation INSTRUMENTATION;
    private static boolean TRY_DYNAMIC_ATTACH = !Boolean.getBoolean("ilivoo.hotSwap.skipDynamicAttach");

    InstrumentationSupport() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
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

    public static Instrumentation instance() {
        if (INSTRUMENTATION != null) {
            return INSTRUMENTATION;
        } else if (TRY_DYNAMIC_ATTACH) {
            try {
                tryDynamicAttach();
                return INSTRUMENTATION;
            } catch (Exception e) {
                throw new RuntimeException("Dynamic Attach failed. You may add this JAR as -javaagent manually, " +
                        "or supply -Djdk.attach.allowAttachSelf");
            }
        } else {
            throw new RuntimeException("No instrumentation. Add this JAR as -javaagent manually.");
        }
    }

    private static void tryDynamicAttach() throws Exception {
        String vmName = "com.sun.tools.attach.VirtualMachine";

        Class vmClass;
        try {
            vmClass = ClassLoader.getSystemClassLoader().loadClass(vmName);
        } catch (Exception var16) {
            String toolsPath = System.getProperty("java.home").replace('\\', '/') + "/../lib/tools.jar";
            URL url = new File(toolsPath).toURI().toURL();
            ClassLoader classLoader = URLClassLoader.newInstance(new URL[]{url});
            vmClass = classLoader.loadClass(vmName);
        }

        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        String pid = runtimeName.substring(0, runtimeName.indexOf('@'));
        Object vm = vmClass.getDeclaredMethod("attach", String.class).invoke(null, pid);

        try {
            File agentFile = File.createTempFile("hotSwapAgent", ".jar");
            try {
                saveAgentJar(agentFile);
                Method loadAgentMethod = vmClass.getDeclaredMethod("loadAgent", String.class, String.class);
                loadAgentMethod.invoke(vm, agentFile.getAbsolutePath(), "");
                Field field = ClassLoader.getSystemClassLoader()
                        .loadClass(InstrumentationSupport.Installer.class.getName())
                        .getDeclaredField("INSTRUMENTATION");
                INSTRUMENTATION = (Instrumentation) field.get(null);
            } finally {
                agentFile.delete();
            }
        } finally {
            vmClass.getDeclaredMethod("detach").invoke(vm);
        }

    }

    private static void saveAgentJar(File agentFile) throws Exception {
        String classDesc = Installer.class.getName().replace('.', '/') + ".class";
        InputStream is = Installer.class.getResourceAsStream('/' + classDesc);
        if (is != null) {
            try {
                Manifest manifest = new Manifest();
                manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
                manifest.getMainAttributes().put(new Name("Agent-Class"), Installer.class.getName());
                manifest.getMainAttributes().put(new Name("Can-Redefine-Classes"), "true");
                JarOutputStream jos = new JarOutputStream(new FileOutputStream(agentFile), manifest);

                try {
                    jos.putNextEntry(new JarEntry(classDesc));
                    byte[] buffer = new byte[1024];

                    int index;
                    while ((index = is.read(buffer)) != -1) {
                        jos.write(buffer, 0, index);
                    }

                    jos.closeEntry();
                } finally {
                    IOUtils.safelyClose(jos);
                }
            } finally {
                IOUtils.safelyClose(is);
            }
        }
    }

    private static class Installer {
        public static volatile Instrumentation INSTRUMENTATION;

        private Installer() {
        }

        public static void agentmain(String agentArgs, Instrumentation inst) {
            INSTRUMENTATION = inst;
        }
    }
}
