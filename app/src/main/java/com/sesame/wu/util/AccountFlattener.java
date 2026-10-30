package com.sesame.wu.util;

import com.elvishew.xlog.flattener.Flattener;
import com.elvishew.xlog.flattener.PatternFlattener;
import com.elvishew.xlog.LogRecord;

import com.sesame.wu.util.idMap.UserIdMap;

/**
 * 在日志行**末尾**追加账号标识，便于区分多个账号的日志。
 *
 * <p>为什么放在行尾而不是行首：日志查看器用
 * {@code ^(\d{2}:\d{2}:\d{2}\.\d{3})\s+(\w+):\s*(.*)$} 解析每一行，
 * 行首加前缀会让正则整行匹配失败、日志页变成一片无法识别的文本。
 *
 * <p>为什么用 uid 后 4 位而不是昵称：昵称会随用户改名/改备注变化，
 * 历史日志就对不上了；后 4 位稳定且足够区分常见的一两个账号。
 */
public final class AccountFlattener implements Flattener {

    private final PatternFlattener delegate;

    public AccountFlattener(String pattern) {
        this.delegate = new PatternFlattener(pattern);
    }

    @Override
    public String format(LogRecord logRecord) {
        String line = delegate.format(logRecord);
        String uid = UserIdMap.getCurrentUid();
        if (uid == null || uid.isEmpty()) {
            return line;
        }
        String tail = uid.length() <= 4 ? uid : uid.substring(uid.length() - 4);
        return line + " [" + tail + "]";
    }
}
