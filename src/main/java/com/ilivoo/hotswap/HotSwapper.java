package com.ilivoo.hotswap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(HotSwapper.class);

    //property const
    private static final String PERIOD = "period";
    private static final String KEEP_TIME = "keepTime";
    private static final String RELOAD_DIR = "reloadDirs";
    private static final String DEVELOP = "develop";
    private static final String RECURSIVE = "recursive";
    private static final String MD5_COMPARE = "md5Compare";

    private static final HotSwapper INSTANCE = new HotSwapper();
    //reload path
    private Set<String> reloadPathSet = new HashSet<String>();
    //hot swap period, default 1 minute
    private volatile long period = Long.getLong(HotSwapInfo.property(PERIOD), 60 * 000l);
    //class keep time after hot swap, default 24 hour
    private volatile long keepTime = Long.getLong(HotSwapInfo.property(KEEP_TIME), 24 * 60 * 60 * 1000l);

    /**
     * use to control hot swap, for online system just use default
     * if use to develop, just set recursive and md5compare are true, and addDelete and relyOn are false
     */
    //reload path recursive dir
    private volatile boolean recursive;
    //use md5 for test last change
    private volatile boolean md5compare;
    //for develop
    private volatile boolean develop;

    private volatile boolean running = false;

    private Thread swapper;
    private long lastSwapTime;
    private long nextSwapTime;

    private Set<HotResource> reloadedResources = new HashSet<HotResource>();

    private HotSwapper() {
        setRecursive(getBoolean(HotSwapInfo.property(RECURSIVE), false));
        setMd5compare(getBoolean(HotSwapInfo.property(MD5_COMPARE), false));

        setDevelop(getBoolean(HotSwapInfo.property(DEVELOP), false));
        LOG.info("hot swap create finish, [{}]", this);
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
        if (argMap.containsKey(KEEP_TIME)) {
            setKeepTime(Long.valueOf(argMap.get(KEEP_TIME)));
        }
        if (argMap.containsKey(MD5_COMPARE)) {
            setMd5compare(Boolean.valueOf(argMap.get(MD5_COMPARE)));
        }
        if (argMap.containsKey(RECURSIVE)) {
            setRecursive(Boolean.valueOf(argMap.get(RECURSIVE)));
        }
        if (argMap.containsKey(DEVELOP)) {
            setDevelop(Boolean.valueOf(argMap.get(DEVELOP)));
        }
        if (argMap.containsKey(RELOAD_DIR)) {
            String[] dirArray = argMap.get(RELOAD_DIR).split(":");
            for (String dir : dirArray) {
                addReloadPath(dir, recursive);
            }
        }
        LOG.info("hot swap parse finish, [{}]", this);
        return this;
    }

    public void addReloadPath(String path, boolean recursive) {
        LOG.info("add reload path begin [{}], {}", path, reloadPathSet);
        File pathDir = new File(path);
        if (!pathDir.exists() || !pathDir.isDirectory()) {
            throw new IllegalArgumentException(String.format("%s is not exist or not a directory !", path));
        }
        String canonicalPath = null;
        try {
            canonicalPath = pathDir.getCanonicalPath();
        } catch (IOException e) {
            canonicalPath = StringUtils.cleanPath(pathDir.getAbsolutePath());
        }
        synchronized (this) {
            reloadPathSet.add(canonicalPath);
            if (recursive) {
                List<String> childrenDirs = IOUtils.walkDirRecursively(pathDir, canonicalPath);
                for (String dir : childrenDirs) {
                    reloadPathSet.add(dir);
                }
            }
            if (develop) {
                List<HotResource> hotResourceList = listHotResource(reloadPathSet, System.currentTimeMillis());
                reloadedResources.addAll(hotResourceList);
            } else {
                IOUtils.deleteRecursively(pathDir, false, !recursive);
            }
        }
        LOG.info("add reload path finish [{}], {}", path, reloadPathSet);
    }

    public synchronized void removeReloadPath(String path, boolean recursive) {
        LOG.info("remove reload path finish [{}], {}", path, reloadPathSet);
        reloadPathSet.remove(path);
        if (!recursive) {
            return;
        }
        for (String reloadPath : reloadPathSet) {
            if (reloadPath.startsWith(path) && reloadPath.charAt(path.length()) == '.') {
                reloadPathSet.remove(reloadPath);
            }
        }
        LOG.info("remove reload path finish [{}], {}", path, reloadPathSet);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        swapper = new SwapThread(getClass().getSimpleName());
        swapper.setDaemon(true);
        swapper.start();
        LOG.info("hot swap started, [{}]", this);
    }

    public synchronized void shutdown() {
        if (!running) {
            return;
        }
        reloadPathSet.clear();
        running = false;
        swapper.interrupt();
        LOG.info("hot swap shutdown, [{}]", this);
    }

    private List<HotResource> listHotResource(Collection<String> paths, long lastSwapTime) {
        List<HotResource> hotResourceList = new ArrayList<HotResource>();
        for (String reloadPath : paths) {
            File file = new File(reloadPath);
            String[] classFileArray = null;
            try {
                classFileArray = file.list(new FilenameFilter() {
                    @Override
                    public boolean accept(File dir, String name) {
                        return new File(dir, name).isFile() && name.endsWith(".class");
                    }
                });
            } catch (Throwable throwable) {
                // nothing to do
                LOG.warn("list file error, reload path [{}]", reloadPath);
            }
            if (classFileArray == null) {
                LOG.debug("reload path empty [{}]", reloadPath);
                continue;
            }
            for (String classFile : classFileArray) {
                HotResource hotResource = null;
                try {
                    hotResource = new HotResource(reloadPath, classFile);
                } catch (Exception e) {
                    LOG.error("resolve error, reload path [{}], file [{}]", reloadPath, classFile);
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
        //clean expire file
        for (Iterator<HotResource> iterator = reloadedResources.iterator(); iterator.hasNext(); ) {
            HotResource hotResource = iterator.next();
            if (hotResource.getLastSwapTime() + keepTime <= currentTime) {
                LOG.info("expire delete reload file [{}]", hotResource);
                hotResource.deleteResource();
                iterator.remove();
            }
        }
        //hot swap
        List<ClassDefinition> cdList = new ArrayList<ClassDefinition>();
        for (Iterator<HotResource> iterator = hotResourceList.iterator(); iterator.hasNext(); ) {
            HotResource hotResource = iterator.next();
            List<Class<?>> loadClassList = null;
            try {
                loadClassList = ClassUtils.forClass(hotResource.getClassName(), null);
            } catch (ClassNotFoundException e) {
            }
            if (loadClassList == null || (loadClassList != null && loadClassList.size() <= 0)) {
                LOG.warn("can not find file [{}], class [{}]", hotResource, hotResource.getClassName());
                iterator.remove();
                continue;
            }
            byte[] classBytes = null;
            try {
                classBytes = hotResource.getBytes();
            } catch (IOException e) {
                LOG.error("error to get [{}] bytes", hotResource.getPath());
                hotResource.deleteResource();
                iterator.remove();
                continue;
            }
            for (Class<?> clazz : loadClassList) {
                LOG.info("add redefine class loader [{}], class [{}]", clazz.getClassLoader(), clazz.getName());
                cdList.add(new ClassDefinition(clazz, classBytes.clone()));
            }
            hotResource.setLastSwapTime(currentTime);
            reloadedResources.add(hotResource);
        }
        if (cdList.size() <= 0) {
            return;
        }
        if (develop) {
            for (ClassDefinition classDefinition : cdList) {
                try {
                    InstrumentationSupport.instance().redefineClasses(classDefinition);
                    LOG.info("redefine success, class loader [{}], class [{}]",
                            classDefinition.getDefinitionClass().getClassLoader(),
                            classDefinition.getDefinitionClass());
                } catch (Exception ex) {
                    //not relyOn on
                    LOG.error("redefine error, class loader [{}], class [{}]",
                            classDefinition.getDefinitionClass().getClassLoader(),
                            classDefinition.getDefinitionClass());
                }
            }
        } else {
            InstrumentationSupport.instance().redefineClasses(cdList.toArray(new ClassDefinition[0]));
        }
    }

    public long getPeriod() {
        return period;
    }

    public void setPeriod(long period) {
        this.period = period;
    }

    public long getKeepTime() {
        return keepTime;
    }

    public void setKeepTime(long keepTime) {
        this.keepTime = keepTime;
    }

    public boolean isRecursive() {
        return recursive;
    }

    void setRecursive(boolean recursive) {
        this.recursive = recursive;
    }

    public boolean isMd5compare() {
        return md5compare;
    }

    void setMd5compare(boolean md5compare) {
        this.md5compare = md5compare;
    }

    public boolean isDevelop() {
        return develop;
    }

    void setDevelop(boolean develop) {
        this.develop = develop;
        if (develop) {
            setRecursive(true);
            setMd5compare(true);
        }
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public String toString() {
        return "HotSwapper{" +
                "period=" + period +
                ", keepTime=" + keepTime +
                ", recursive=" + recursive +
                ", md5compare=" + md5compare +
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
                            LOG.debug("ready to hot swap [{}]", HotSwapper.this);
                            hotSwap(currentTime);
                        } else {
                            HotSwapper.this.wait(period);
                        }
                    } catch (Throwable ex) {
                        // nothing to do
                        LOG.warn("hot swap round error", ex);
                    }
                }
            }
        }
    }
}
