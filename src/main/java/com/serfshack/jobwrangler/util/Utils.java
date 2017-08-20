package com.serfshack.jobwrangler.util;

public class Utils {
    public static boolean equals(String a, String b) {
        if (a == null)
            return b == null;
        return a.equals(b);
    }

    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    public static void checkNull(Object o, String s) {
        if (o == null) {
            throw new IllegalArgumentException(s);
        }
    }
}
