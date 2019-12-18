package com.ilivoo.hotswap;

public class HotSwapInfo {

    private static final String PROPERTY_PREFIX = "ilivoo.hotSwap.";

    private HotSwapInfo() {

    }

    public static String property(String name) {
        if (name == null || (name != null && name.length() <= 0)) {
            throw new IllegalArgumentException("property name must not null");
        }
        return PROPERTY_PREFIX + name;
    }
}
