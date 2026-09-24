package com.surexu.sesame.util;

import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;

public class StringUtil {
    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    public static String collectionJoinString(CharSequence conjunction, Collection<?> collection) {
        if (!collection.isEmpty()) {
            StringBuilder b = new StringBuilder();
            Iterator<?> iterator = collection.iterator();
            b.append(toStringOrEmpty(iterator.next()));
            while (iterator.hasNext()) {
                b.append(conjunction).append(toStringOrEmpty(iterator.next()));
            }
            return b.toString();
        }
        return "";
    }

    public static String arrayJoinString(CharSequence conjunction, Object... array) {
        int length = array.length;
        if (length > 0) {
            StringBuilder b = new StringBuilder();
            b.append(toStringOrEmpty(array[0]));
            for (int i = 1; i < length; i++) {
                b.append(conjunction).append(toStringOrEmpty(array[i]));
            }
            return b.toString();
        }
        return "";
    }

    public static String arrayToString(Object... array) {
        return arrayJoinString(",", array);
    }

    private static String toStringOrEmpty(Object obj) {
        return Objects.toString(obj, "");
    }

    public static String padLeft(int str, int totalWidth, char padChar) {
        return padLeft(String.valueOf(str), totalWidth, padChar);
    }

    public static String padRight(int str, int totalWidth, char padChar) {
        return padRight(String.valueOf(str), totalWidth, padChar);
    }

    public static String padLeft(String str, int totalWidth, char padChar) {
        StringBuilder sb = new StringBuilder(str);
        while (sb.length() < totalWidth) {
            sb.insert(0, padChar);
        }
        return sb.toString();
    }

    public static String padRight(String str, int totalWidth, char padChar) {
        StringBuilder sb = new StringBuilder(str);
        while (sb.length() < totalWidth) {
            sb.append(padChar);
        }
        return sb.toString();
    }

    public static String getSubString(String text, String left, String right) {
        int leftIndex = isEmpty(left) ? 0 : text.indexOf(left);
        if (leftIndex == -1) {
            return "";
        } else if (!isEmpty(left)) {
            leftIndex += left.length();
        }
        int rightIndex = isEmpty(right) ? text.length() : text.indexOf(right, leftIndex);
        if (rightIndex == -1) {
            return "";
        }
        return text.substring(leftIndex, rightIndex);
    }

    /**
     * 取 URL 查询参数值（等价于 android.net.Uri#getQueryParameter，会做 URL 解码）。
     *
     * @param url 完整 URL
     * @param key 参数名
     * @return 参数值；URL 中不存在该参数时返回 null
     */
    public static String getUrlQueryParam(String url, String key) {
        if (isEmpty(url) || isEmpty(key)) {
            return null;
        }
        int queryIndex = url.indexOf('?');
        if (queryIndex < 0) {
            return null;
        }
        String query = url.substring(queryIndex + 1);
        int fragmentIndex = query.indexOf('#');
        if (fragmentIndex >= 0) {
            query = query.substring(0, fragmentIndex);
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !key.equals(pair.substring(0, eq))) {
                continue;
            }
            String value = pair.substring(eq + 1);
            try {
                return java.net.URLDecoder.decode(value, "UTF-8");
            } catch (Throwable th) {
                return value;
            }
        }
        return null;
    }

}
