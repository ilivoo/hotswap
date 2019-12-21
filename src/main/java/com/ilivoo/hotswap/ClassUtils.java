package com.ilivoo.hotswap;

import java.util.ArrayList;
import java.util.List;

class ClassUtils {
    private ClassUtils() {

    }

    public static List<Class<?>> forClass(String name, ClassLoader loader) throws ClassNotFoundException {
        ClassLoader clToUse = loader;
        if (clToUse == null) {
            //there has no need to guess which classloader will use
            //clToUse = getDefaultClassLoader();
        }
        Class<?> loadClass = null;
        try {
            loadClass = clToUse != null ? clToUse.loadClass(name) : null;
        } catch (ClassNotFoundException ex) {
            //class not found, may be loader is not correct
        }
        List<Class<?>> resultList = new ArrayList<Class<?>>();
        if (loadClass != null) {
            resultList.add(loadClass);
        }
        // for instrumentation
        if (loadClass == null && Instrumentation.supportInstrument()) {
            Class[] allLoadedClasses = Instrumentation.instance().getAllLoadedClasses();
            for (Class clazz : allLoadedClasses) {
                if (clazz.getName().equals(name)) {
                    resultList.add(clazz);
                }
            }
        }
        if (resultList.size() == 0) {
            throw new ClassNotFoundException(name);
        }
        return resultList;
    }

    public static ClassLoader getDefaultClassLoader() {
        ClassLoader cl = null;
        try {
            cl = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ex) {
            // Cannot access thread context ClassLoader - falling back...
        }
        if (cl == null) {
            // No thread context class loader -> use class loader of this class.
            cl = ClassUtils.class.getClassLoader();
            if (cl == null) {
                // getClassLoader() returning null indicates the bootstrap ClassLoader
                try {
                    cl = ClassLoader.getSystemClassLoader();
                } catch (Throwable ex) {
                    // Cannot access system ClassLoader - oh well, maybe the caller can live with null...
                }
            }
        }
        return cl;
    }
}
