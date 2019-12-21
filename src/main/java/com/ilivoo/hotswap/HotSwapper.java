package com.ilivoo.hotswap;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HotSwapper {
    //property const
    private static final String PERIOD = "period";
    private static final String RELOAD_DIR = "reloadDirs";
    private static final String DEVELOP = "develop";

    private static final HotSwapper INSTANCE = new HotSwapper();
    //reload path
    private Set<String> reloadPathSet = new HashSet<String>();
    //hot swap period, default 1 minute
    private volatile long period = Long.getLong(HotSwapInfo.property(PERIOD), 60 * 000l);
    private volatile boolean develop;
    private volatile boolean running = false;

    private Thread swapper;
    private long lastSwapTime;
    private long nextSwapTime;

    private Set<HotResource> reloadedResources = new HashSet<HotResource>();

    private HotSwapper() {
        setDevelop(getBoolean(HotSwapInfo.property(DEVELOP), false));
    }

    public static HotSwapper instance() {
        return INSTANCE;
    }

    private boolean getBoolean(String nm, boolean val) {
        String v = null;
        try {
            v = System.getProperty(nm);
        } catch (Exception e) {
        }
        if (v != null) {
            return Boolean.parseBoolean(v);
        }
        return val;
    }

    HotSwapper parseProperty(String agentArgs) {
        Map<String, String> argMap = new HashMap<String, String>();
        if (agentArgs != null && !agentArgs.trim().equals("")) {
            String[] argArray = agentArgs.split(",");
            int argLength = argArray.length;
            for (int i = 0; i < argLength; ++i) {
                String nameValue = argArray[i];
                String[] nvPair = nameValue.split("=", 2);
                String name = nvPair[0].trim();
                String value = nvPair.length > 1 ? nvPair[1].trim() : "";
                argMap.put(name, value);
            }
        }
        if (argMap.containsKey(PERIOD)) {
            setPeriod(Long.valueOf(argMap.get(PERIOD)));
        }
        if (argMap.containsKey(DEVELOP)) {
            setDevelop(Boolean.valueOf(argMap.get(DEVELOP)));
        }
        if (argMap.containsKey(RELOAD_DIR)) {
            String[] dirArray = argMap.get(RELOAD_DIR).split(":");
            for (String dir : dirArray) {
                addReloadPath(dir);
            }
        }
        System.out.println(String.format("hot swap parse finish, %s", this));
        return this;
    }

    public void addReloadPath(String path) {
        System.out.println(String.format("add reload path begin [%s], %s", path, reloadPathSet));
        File pathDir = new File(path);
        if (!pathDir.exists() || !pathDir.isDirectory()) {
            throw new IllegalArgumentException(String.format("%s is not exist or not a directory !", path));
        }
        String canonicalPath;
        try {
            canonicalPath = pathDir.getCanonicalPath();
        } catch (IOException e) {
            canonicalPath = StringUtils.cleanPath(pathDir.getAbsolutePath());
        }
        synchronized (this) {
            reloadPathSet.add(canonicalPath);
            if (develop) {
                List<String> childrenDirs = IOUtils.walkDirRecursively(pathDir, canonicalPath);
                for (String dir : childrenDirs) {
                    reloadPathSet.add(dir);
                }
                List<HotResource> hotResourceList = listHotResource(reloadPathSet, System.currentTimeMillis());
                reloadedResources.addAll(hotResourceList);
            } else {
                IOUtils.deleteRecursively(pathDir, false, !develop);
            }
        }
        System.out.println(String.format("add reload path finish [%s], %s", path, reloadPathSet));
    }

    public synchronized void removeReloadPath(String path, boolean recursive) {
        System.out.println(String.format("remove reload path finish [%s], %s", path, reloadPathSet));
        reloadPathSet.remove(path);
        if (!recursive) {
            return;
        }
        for (String reloadPath : reloadPathSet) {
            if (reloadPath.startsWith(path) && reloadPath.charAt(path.length()) == '.') {
                reloadPathSet.remove(reloadPath);
            }
        }
        System.out.println(String.format("remove reload path finish [%s], %s", path, reloadPathSet));
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        swapper = new SwapThread(getClass().getSimpleName());
        swapper.setDaemon(true);
        swapper.start();
        System.out.println(String.format("hot swap started, [%s]", this));
    }

    public synchronized void shutdown() {
        if (!running) {
            return;
        }
        reloadPathSet.clear();
        running = false;
        swapper.interrupt();
        System.out.println(String.format("hot swap shutdown, [%s]", this));
    }

    private List<HotResource> listHotResource(Collection<String> paths, long lastSwapTime) {
        List<HotResource> hotResourceList = new ArrayList<HotResource>();
        for (String reloadPath : paths) {
            File file = new File(reloadPath);
            String[] classFileArray = null;
            try {
                classFileArray = file.list(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        return new File(dir, name).isFile() && name.endsWith(".class");
                    }
                });
            } catch (Throwable throwable) {
                // nothing to do
                System.err.println(String.format("list file error, reload path [%s]", reloadPath));
            }
            if (classFileArray == null) {
                continue;
            }
            for (String classFile : classFileArray) {
                HotResource hotResource = null;
                try {
                    hotResource = new HotResource(reloadPath, classFile);
                } catch (Exception e) {
                    System.err.println(String.format("resolve error, reload path [%s], file [%s]", reloadPath, classFile));
                    hotResource.deleteResource();
                    continue;
                }
                if (reloadedResources.contains(hotResource)) {
                    continue;
                }
                if (lastSwapTime > 0) {
                    hotResource.setLastSwapTime(lastSwapTime);
                }
                hotResourceList.add(hotResource);
            }
        }
        return hotResourceList;
    }

    private void hotSwap(long currentTime) throws Exception {
        List<HotResource> hotResourceList = listHotResource(reloadPathSet, 0);
        //hot swap
        List<ClassDefinition> cdList = new ArrayList<ClassDefinition>();
        for (Iterator<HotResource> iterator = hotResourceList.iterator(); iterator.hasNext(); ) {
            HotResource hotResource = iterator.next();
            List<Class<?>> loadClassList = null;
            try {
                loadClassList = ClassUtils.forClass(hotResource.getClassName(), null);
            } catch (ClassNotFoundException e) {
                if (!develop) {
                    System.err.println("can not find need loaded class " + hotResource.getClassName());
                }
            }
            if (loadClassList == null || (loadClassList != null && loadClassList.size() <= 0)) {
                System.err.println(String.format("can not find file [%s], class [%s]", hotResource, hotResource.getClassName()));
                iterator.remove();
                continue;
            }
            byte[] classBytes;
            try {
                classBytes = hotResource.getBytes();
            } catch (IOException e) {
                System.err.println(String.format("error to get [%s] bytes", hotResource.getPath()));
                hotResource.deleteResource();
                iterator.remove();
                continue;
            }
            for (Class<?> clazz : loadClassList) {
                System.out.println(String.format("add redefine class loader [%s], class [%s]", clazz.getClassLoader(), clazz.getName()));
                cdList.add(new ClassDefinition(clazz, classBytes.clone()));
            }
            hotResource.setLastSwapTime(currentTime);
            if (!develop) {
                hotResource.deleteResource();
            }
            reloadedResources.add(hotResource);
        }
        if (cdList.size() <= 0) {
            return;
        }
        System.out.println("start transform class");
        if (develop) {
            for (ClassDefinition classDefinition : cdList) {
                try {
                    Instrumentation.instance().redefineClasses(classDefinition);
                    System.out.println(String.format("redefine success, class loader [%s], class [%s]",
                            classDefinition.getDefinitionClass().getClassLoader(),
                            classDefinition.getDefinitionClass()));
                } catch (Exception ex) {
                    //not relyOn on
                    System.err.println(String.format("redefine error, class loader [%s], class [%s]",
                            classDefinition.getDefinitionClass().getClassLoader(),
                            classDefinition.getDefinitionClass()));
                }
            }
        } else {
            Instrumentation.instance().redefineClasses(cdList.toArray(new ClassDefinition[0]));
            for (ClassDefinition classDefinition : cdList) {
                System.out.println(String.format("redefine success, class loader [%s], class [%s]",
                        classDefinition.getDefinitionClass().getClassLoader(),
                        classDefinition.getDefinitionClass()));
            }
            reloadedResources.clear();
        }
    }

    public long getPeriod() {
        return period;
    }

    public void setPeriod(long period) {
        this.period = period;
    }

    public boolean isDevelop() {
        return develop;
    }

    void setDevelop(boolean develop) {
        this.develop = develop;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public String toString() {
        return "HotSwapper{" +
                "period=" + period +
                ", develop=" + develop +
                ", running=" + running +
                ", lastSwapTime=" + lastSwapTime +
                ", nextSwapTime=" + nextSwapTime +
                '}';
    }

    private class SwapThread extends Thread {

        public SwapThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            synchronized (HotSwapper.this) {
                while (running) {
                    try {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime > nextSwapTime) {
                            lastSwapTime = currentTime;
                            nextSwapTime = lastSwapTime + period;
                            hotSwap(currentTime);
                        } else {
                            HotSwapper.this.wait(period);
                        }
                    } catch (Throwable ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }
}
