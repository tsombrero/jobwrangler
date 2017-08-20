package com.serfshack.jobwrangler.util;

public class Log {

    private static long t = System.currentTimeMillis();

    public static void v(String str, Object... args) {
        System.out.println(header() + String.format(str, args));
    }
    public static void d(String str, Object... args) {
        System.out.println(header() + String.format(str, args));
    }
    public static void i(String str, Object... args) {
        System.out.println(header() + String.format(str, args));
    }
    public static void w(String str, Object... args) {
        System.out.println(header() + String.format(str, args));
    }
    public static void e(String str, Object... args) {
        System.out.println(header() + String.format(str, args));
    }

    public static void v(Throwable e, String str, Object... args) {
        System.out.println(header() + String.format(str, args));
        e.printStackTrace();
    }

    public static void d(Throwable e, String str, Object... args) {
        System.out.println(header() + String.format(str, args));
        e.printStackTrace();
    }

    public static void i(Throwable e, String str, Object... args) {
        System.out.println(header() + String.format(str, args));
        e.printStackTrace();
    }

    public static void w(Throwable e, String str, Object... args) {
        System.out.println(header() + String.format(str, args));
        e.printStackTrace();
    }

    public static void e(Throwable e, String str, Object... args) {
        System.out.println(header() + String.format(str, args));
        e.printStackTrace();
    }

    private static String header() {
        return String.format("%08d (%s) ", System.currentTimeMillis()-t, Thread.currentThread().getName());
    }

    public static void v(Throwable e) {
        Log.e(e.toString());
        e.printStackTrace();
    }

    public static void d(Throwable e) {
        Log.e(e.toString());
        e.printStackTrace();
    }

    public static void i(Throwable e) {
        Log.e(e.toString());
        e.printStackTrace();
    }

    public static void w(Throwable e) {
        Log.e(e.toString());
        e.printStackTrace();
    }

    public static void e(Throwable e) {
        Log.e(e.toString());
        e.printStackTrace();
    }
}
