package com.surexu.sesame.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.surexu.sesame.model.normal.base.BaseModel;
import com.surexu.sesame.util.Log;

public class Toast {
    private static final String TAG = Toast.class.getSimpleName();

    public static void show(CharSequence cs) {
        show(cs, false);
    }

    public static void show(CharSequence cs, boolean force) {
        Context context = ApplicationHook.getContext();
        if (context == null) {
            return;
        }
        if (force || BaseModel.getShowToast().getValue()) {
            displayToast(context.getApplicationContext(), cs);
        }
    }

    /**
     * Sesame-AG 风格：自己创建主线程 Handler，不依赖 ApplicationHook.getMainHandler()，
     * 避免因 hook 初始化失败导致 mainHandler 为 null 而抛出 NullPointerException。
     */
    private static void displayToast(Context context, CharSequence message) {
        try {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            if (Looper.myLooper() == Looper.getMainLooper()) {
                createAndShowToast(context, message);
            } else {
                mainHandler.post(() -> createAndShowToast(context, message));
            }
        } catch (Throwable t) {
            Log.i(TAG, "displayToast err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void createAndShowToast(Context context, CharSequence message) {
        try {
            android.widget.Toast toast = android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT);
            toast.setGravity(toast.getGravity(), toast.getXOffset(), BaseModel.getToastOffsetY().getValue());
            toast.show();
        } catch (Throwable t) {
            Log.i(TAG, "createAndShowToast err:");
            Log.printStackTrace(TAG, t);
        }
    }
}