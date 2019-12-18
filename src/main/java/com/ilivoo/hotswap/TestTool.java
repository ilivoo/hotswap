package com.ilivoo.hotswap;

import org.slf4j.LoggerFactory;

import java.util.Scanner;

public class TestTool {

    private int num;

    public void say() {
        System.out.println("Hello: " + num++);
    }

    public static void main(String[] args) {
        final TestTool test = new TestTool();
        HotSwapper.instance().setPeriod(10000l);
        HotSwapper.instance().setDevelop(true);
        HotSwapper.instance().start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(10 * 1000l);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    test.say();
                }
            }
        }).start();
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNext()) {
            command(scanner.nextLine());
        }
    }

    public static void command(String line) {
        try {
            LoggerFactory.getLogger(HotSwapper.class).info("Log line [{}]", line);
            String[] lineArgs = line.split(" ");
            if (line.startsWith("add")) {//add target/classes
                HotSwapper.instance().addReloadPath(lineArgs[1], true);
            } else if (line.startsWith("period")) {// period 10000
                HotSwapper.instance().setPeriod(Long.valueOf(lineArgs[1]));
            } else if (line.startsWith("keepTime")) {
                HotSwapper.instance().setKeepTime(Long.valueOf(lineArgs[1]));
            } else if (line.startsWith("classPath")){
                System.out.println("class path" + System.getProperty("java.class.path"));
            }
            System.out.println("cmd completed!");
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }
}
