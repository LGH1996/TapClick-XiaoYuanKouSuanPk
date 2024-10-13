package com.lgh.tapclick.myfunction;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lgh.advertising.tapclick.R;
import com.lgh.advertising.tapclick.databinding.ViewAddDataBinding;
import com.lgh.advertising.tapclick.databinding.ViewDbClickSettingBinding;
import com.lgh.advertising.tapclick.databinding.ViewDialogWarningBinding;
import com.lgh.advertising.tapclick.databinding.ViewWidgetSelectBinding;
import com.lgh.tapclick.myactivity.EditDataActivity;
import com.lgh.tapclick.myactivity.MainActivity;
import com.lgh.tapclick.mybean.AppDescribe;
import com.lgh.tapclick.mybean.Coordinate;
import com.lgh.tapclick.mybean.Widget;
import com.lgh.tapclick.myclass.DataDao;
import com.lgh.tapclick.myclass.MyApplication;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/**
 * adb shell pm grant com.lgh.advertising.going android.permission.WRITE_SECURE_SETTINGS
 * adb shell settings put secure enabled_accessibility_services com.lgh.tapclick/com.lgh.tapclick.myfunction.MyAccessibilityService
 * adb shell settings put secure accessibility_enabled 1
 * <p>
 * Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, getPackageName() + "/" + MyAccessibilityService.class.getName());
 * Settings.Secure.putString(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1");
 */

public class MainFunction {
    private final String isScreenOffPre = "isScreenOffPre";
    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final PackageManager packageManager;
    private final InputMethodManager inputMethodManager;
    private final Handler handler;
    private final DataDao dataDao;
    private final Map<String, AppDescribe> appDescribeMap;
    private final ScheduledExecutorService executorServiceMain;
    private final ScheduledExecutorService executorServiceSub;
    private final Set<Widget> alreadyClickSet;
    private final Set<Widget> debounceSet;
    private final LinkedList<String> logList;
    private final Gson gson;
    private final Gson gsonNoPretty;
    private final SimpleDateFormat simpleDateFormat;
    private volatile AppDescribe appDescribe;
    private volatile String currentPackage;
    private volatile String currentPackageSub;
    private volatile String currentActivity;
    private volatile boolean onOffWidget;
    private volatile boolean onOffWidgetSub;
    private volatile boolean onOffCoordinate;
    private volatile boolean onOffCoordinateSub;
    private volatile boolean needChangeActivity;
    private volatile ScheduledFuture<?> futureWidget;
    private volatile ScheduledFuture<?> futureCoordinate;
    private volatile Set<Widget> widgetSet;
    private volatile Map<String, Coordinate> coordinateMap;
    private volatile Map<String, Set<Widget>> widgetSetMap;
    private volatile Coordinate coordinate;
    private volatile ScheduledFuture<?> futureCoordinateClick;
    private volatile AccessibilityServiceInfo serviceInfo;
    private volatile Object preTrigger;
    private MyBroadcastReceiver myBroadcastReceiver;
    private MyPackageReceiver myPackageReceiver;
    private WindowManager.LayoutParams aParams, bParams, cParams;
    private ViewAddDataBinding addDataBinding;
    private ViewWidgetSelectBinding widgetSelectBinding;
    private ImageView viewClickPosition;
    private Set<String> pkgSuggestNotOnList;
    private View ignoreView;
    private WindowManager.LayoutParams dbClickLp;
    private View dbClickView;
    private DisplayMetrics displayMetrics;

    public MainFunction(AccessibilityService accessibilityService) {
        service = accessibilityService;
        inputMethodManager = accessibilityService.getSystemService(InputMethodManager.class);
        windowManager = accessibilityService.getSystemService(WindowManager.class);
        packageManager = accessibilityService.getPackageManager();
        executorServiceMain = Executors.newSingleThreadScheduledExecutor();
        executorServiceSub = Executors.newSingleThreadScheduledExecutor();
        handler = new Handler(Looper.getMainLooper());
        simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        gson = new GsonBuilder().setPrettyPrinting().create();
        gsonNoPretty = new GsonBuilder().create();
        alreadyClickSet = new HashSet<>();
        appDescribeMap = new HashMap<>();
        debounceSet = new HashSet<>();
        logList = new LinkedList<>();
        dataDao = MyApplication.dataDao;
        currentPackage = "Initialize CurrentPackage";
        currentActivity = "Initialize CurrentActivity";
        displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(displayMetrics);
    }

    protected void onServiceConnected() {
        serviceInfo = service.getServiceInfo();
        currentPackageSub = currentPackage;
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        myBroadcastReceiver = new MyBroadcastReceiver();
        service.registerReceiver(myBroadcastReceiver, intentFilter);
        IntentFilter filterPackage = new IntentFilter();
        filterPackage.addAction(Intent.ACTION_PACKAGE_ADDED);
        filterPackage.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filterPackage.addDataScheme("package");
        myPackageReceiver = new MyPackageReceiver();
        service.registerReceiver(myPackageReceiver, filterPackage);
        keepAliveByNotification(MyUtils.getKeepAliveByNotification());
        keepAliveByFloatingWindow(MyUtils.getKeepAliveByFloatingWindow());
        showDbClickFloating(MyUtils.getDbClickEnable());
        executorServiceSub.execute(new Runnable() {
            @Override
            public void run() {
                getRunningData();
            }
        });
        futureCoordinate = futureWidget = futureCoordinateClick = executorServiceSub.schedule(new Runnable() {
            @Override
            public void run() {
            }
        }, 0, TimeUnit.MILLISECONDS);

        /*executorService.schedule(new Runnable() {
            @Override
            public void run() {
                NotificationManager notificationManager = service.getSystemService(NotificationManager.class);
                Notification.Builder builder = new Notification.Builder(service)
                        .setAutoCancel(true)
                        .setSmallIcon(R.drawable.app)
                        .setContentTitle(service.getText(R.string.app_name))
                        .setContentText("占用内存" + Debug.getPss() + "kb");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder.setChannelId(service.getPackageName());
                    NotificationChannel channel = new NotificationChannel(service.getPackageName(), service.getString(R.string.app_name), NotificationManager.IMPORTANCE_HIGH);
                    notificationManager.createNotificationChannel(channel);
                }
                notificationManager.notify(service.getPackageName(), 0x01, builder.build());
                executorService.schedule(this, 5000, TimeUnit.MILLISECONDS);
            }
        }, 0, TimeUnit.MILLISECONDS);*/

//        handler.postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                xiaoYuanKouSuan(service.getRootInActiveWindow());
//                handler.postDelayed(this, 100);
//            }
//        }, 100);
    }

    public List<Path> getPathByStr(String numStr) {
        float x = 200;
        float y = ((float) displayMetrics.heightPixels / 5) * 3;
        List<Path> list = new ArrayList<>();
        for (int n = 0; n < numStr.length(); n++) {
            String charStr = numStr.substring(n, n + 1);
            Path path = getPathByChar(charStr, x + 100 * n + 50 * n, y);
            list.add(path);
        }
        return list;
    }

    public Path getPathByChar(String charStr, float x, float y) {
        float width = 100;
        float height = 150;
        if (TextUtils.equals(charStr, "0")) {
            Path path1 = new Path();
            path1.moveTo(x, y);
            path1.lineTo(x + width, y);
            path1.lineTo(x + width, y + height);
            path1.lineTo(x, y + height);
            path1.lineTo(x, y);
            path1.close();
            return path1;
        } else if (TextUtils.equals(charStr, "1")) {
            Path path1 = new Path();
            path1.moveTo(x + width / 2, y);
            path1.lineTo(x + width / 2, y + height);
            return path1;
        } else if (TextUtils.equals(charStr, "2")) {
            Path path1 = new Path();
            path1.moveTo(x, y);
            path1.lineTo(x + width, y);
            path1.lineTo(x, y + height);
            path1.lineTo(x + width, y + height);
            return path1;
        } else if (TextUtils.equals(charStr, "3")) {
            Path path1 = new Path();
            path1.moveTo(x, y);
            path1.lineTo(x + width, y + height / 4);
            path1.lineTo(x, y + height / 2);
            path1.lineTo(x + width, y + (height / 4) * 3);
            path1.lineTo(x, y + height);
            return path1;
        } else if (TextUtils.equals(charStr, "4")) {
            Path path1 = new Path();
            path1.moveTo(x + width, y + height / 2);
            path1.lineTo(x, y + height / 2);
            path1.lineTo(x + width / 2, y);
            path1.lineTo(x + width / 2, y + height + 50);
            return path1;
        } else if (TextUtils.equals(charStr, "5")) {
            Path path1 = new Path();
            path1.moveTo(x + width, y);
            path1.lineTo(x, y);
            path1.lineTo(x, y + height / 2);
            path1.lineTo(x + width, y + height / 2);
            path1.lineTo(x, y + height);
            return path1;
        } else if (TextUtils.equals(charStr, "6")) {
            Path path1 = new Path();
            path1.moveTo(x + width / 2, y);
            path1.lineTo(x, y + height / 2);
            path1.lineTo(x, y + height);
            path1.lineTo(x + width, y + (height / 4) * 3);
            path1.lineTo(x, y + height / 2);
            return path1;
        } else if (TextUtils.equals(charStr, "7")) {
            Path path1 = new Path();
            path1.moveTo(x, y);
            path1.lineTo(x + width, y);
            path1.lineTo(x + width, y + height);
            return path1;
        } else if (TextUtils.equals(charStr, "8")) {
            Path path1 = new Path();
            path1.moveTo(x, y);
            path1.lineTo(x + width, y);
            path1.lineTo(x, y + height);
            path1.lineTo(x + width, y + height);
            path1.lineTo(x, y);
            path1.close();
            return path1;
        } else if (TextUtils.equals(charStr, "9")) {
            Path path1 = new Path();
            path1.moveTo(x + width, y + height / 2);
            path1.lineTo(x, y + height / 4);
            path1.lineTo(x + width, y);
            path1.lineTo(x + width, y + height + 50);
            return path1;
        } else if (TextUtils.equals(charStr, ".")) {
            Path path1 = new Path();
            path1.moveTo(x + width / 2 - 2, y + height);
            path1.lineTo(x + width / 2 + 2, y + height);
            return path1;
        } else {
            throw new RuntimeException("非法字符");
        }
    }

    @SuppressLint("SwitchIntDef")
    public void onAccessibilityEvent(AccessibilityEvent event) {
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root == null) {
                    break;
                }
                String packageName = root.getPackageName() != null ? root.getPackageName().toString() : null;
                String activityName = event.getClassName() != null ? event.getClassName().toString() : null;
                if (packageName == null) {
                    break;
                }
                List<AccessibilityWindowInfo> windowInfoList = service.getWindows();
                if (windowInfoList.isEmpty()) {
                    break;
                }
                List<AccessibilityNodeInfo> nodeInfoList = new ArrayList<>();
                for (AccessibilityWindowInfo windowInfo : windowInfoList) {
                    AccessibilityNodeInfo nodeInfo = windowInfo.getRoot();
                    if (nodeInfo != null && TextUtils.equals(nodeInfo.getPackageName(), packageName)) {
                        nodeInfoList.add(nodeInfo);
                    }
                }
                if (!packageName.equals(currentPackage)) {
                    addLog("打开应用：" + packageName);
                    currentPackage = packageName;
                    appDescribe = appDescribeMap.get(packageName);
                }
                if (appDescribe == null) {
                    break;
                }
                if (!event.isFullScreen()
                        && !appDescribe.coordinateOnOff
                        && !appDescribe.widgetOnOff
                        && !currentPackageSub.equals(isScreenOffPre)
                        && !currentActivity.equals(isScreenOffPre)) {
                    break;
                }
                if (!packageName.equals(currentPackageSub)) {
                    needChangeActivity = true;
                    currentPackageSub = packageName;
                    futureWidget.cancel(false);
                    futureCoordinate.cancel(false);
                    serviceInfo.eventTypes &= ~AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
                    service.setServiceInfo(serviceInfo);
                    debounceSet.clear();
                    onOffWidgetSub = false;
                    onOffCoordinateSub = false;
                    widgetSetMap = appDescribe.widgetSetMap;
                    coordinateMap = appDescribe.coordinateMap;
                    onOffWidget = (appDescribe.widgetOnOff && !widgetSetMap.isEmpty()) || TextUtils.equals(currentPackage, "com.fenbi.android.leo");
                    onOffCoordinate = appDescribe.coordinateOnOff && !coordinateMap.isEmpty();

                    if (onOffWidget && !appDescribe.widgetRetrieveAllTime) {
                        futureWidget = executorServiceSub.schedule(new Runnable() {
                            @Override
                            public void run() {
                                onOffWidget = false;
                                onOffWidgetSub = false;
                                serviceInfo.eventTypes &= ~AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
                                service.setServiceInfo(serviceInfo);
                            }
                        }, appDescribe.widgetRetrieveTime, TimeUnit.MILLISECONDS);
                    }

                    if (onOffCoordinate && !appDescribe.coordinateRetrieveAllTime) {
                        futureCoordinate = executorServiceSub.schedule(new Runnable() {
                            @Override
                            public void run() {
                                onOffCoordinate = false;
                                onOffCoordinateSub = false;
                            }
                        }, appDescribe.coordinateRetrieveTime, TimeUnit.MILLISECONDS);
                    }
                }

                if (activityName == null) {
                    break;
                }
                if (!TextUtils.equals(event.getPackageName(), currentPackage)) {
                    break;
                }
                if ((!activityName.equals(currentActivity)
                        && !activityName.startsWith("android.view.")
                        && !activityName.startsWith("android.widget."))
                        || (activityName.equals("android.widget.FrameLayout")
                        && needChangeActivity)) {
                    addLog("进入页面：" + activityName);
                    serviceInfo.eventTypes &= ~AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
                    service.setServiceInfo(serviceInfo);
                    needChangeActivity = false;
                    currentActivity = activityName;
                    alreadyClickSet.clear();
                    coordinate = coordinateMap != null ? coordinateMap.get(activityName) : null;
                    widgetSet = widgetSetMap != null ? widgetSetMap.get(activityName) : null;
                    onOffCoordinateSub = onOffCoordinate && coordinate != null;
                    onOffWidgetSub = (onOffWidget && widgetSet != null) || TextUtils.equals(currentActivity, "com.yuanfudao.android.leo.webview.ui.activity.SimpleWebAppFireworkActivity");
                    preQuestion = null;

                    if (onOffWidgetSub) {
                        serviceInfo.eventTypes |= AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
                        service.setServiceInfo(serviceInfo);
                    }

                    if (onOffCoordinateSub) {
                        futureCoordinateClick.cancel(false);
                        futureCoordinateClick = executorServiceSub.scheduleWithFixedDelay(new Runnable() {
                            private final Coordinate coordinateSub = coordinate;
                            private int num = 0;

                            @Override
                            public void run() {
                                if (onOffCoordinateSub && num++ < coordinateSub.clickNumber && currentActivity.equals(coordinateSub.appActivity)) {
                                    click(coordinateSub.xPosition, coordinateSub.yPosition);
                                } else {
                                    throw new RuntimeException();
                                }
                                if (num == 1) {
                                    coordinateSub.triggerCount += 1;
                                    coordinateSub.lastTriggerTime = System.currentTimeMillis();
                                    dataDao.updateCoordinate(coordinateSub);
                                    showToast(coordinateSub, coordinateSub.toast);
                                    addLog("点击坐标：" + gson.toJson(coordinateSub));
                                }
                            }
                        }, coordinate.clickDelay, coordinate.clickInterval, TimeUnit.MILLISECONDS);
                    }
                }
                if (nodeInfoList.isEmpty()) {
                    break;
                }
                if (!onOffWidgetSub) {
                    break;
                }
                executorServiceMain.execute(new Runnable() {
                    @Override
                    public void run() {
                        findAndClickView(nodeInfoList, widgetSet);
                    }
                });
                xiaoYuanKouSuan(root);
                break;
            }
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                if (!TextUtils.equals(event.getPackageName(), currentPackageSub)) {
                    break;
                }
                AccessibilityNodeInfo source = event.getSource();
                if (source == null) {
                    break;
                }
                if (!onOffWidgetSub) {
                    break;
                }
                executorServiceMain.execute(new Runnable() {
                    @Override
                    public void run() {
                        findAndClickView(Collections.singletonList(source), widgetSet);
                    }
                });
                xiaoYuanKouSuan(service.getRootInActiveWindow());
                break;
            }
        }
    }

    private volatile String preQuestion;
    static ScriptEngine jse = new ScriptEngineManager().getEngineByName("rhino");
    private static final Pattern pattern = Pattern.compile("([0-9])\\1{4}");

    private void xiaoYuanKouSuan(AccessibilityNodeInfo root) {
        if (root == null) {
            return;
        }
        if (TextUtils.equals(currentPackage, "com.fenbi.android.leo")
                && TextUtils.equals(currentActivity, "com.yuanfudao.android.leo.webview.ui.activity.SimpleWebAppFireworkActivity")) {
            List<AccessibilityNodeInfo> nodeInfoList = findAllNode(Collections.singletonList(root));
            List<AccessibilityNodeInfo> list = findByText(nodeInfoList, ":");
            if (list.isEmpty()) {
                return;
            }
            list = findByText(nodeInfoList, "=");
            if (list.isEmpty()) {
                return;
            }
            for (AccessibilityNodeInfo nodeInfo : list) {
                if ((nodeInfo.getParent() != null && TextUtils.equals(nodeInfo.getParent().getViewIdResourceName(), "primary-question-wrap"))
                        || (nodeInfo.getParent().getParent() != null && TextUtils.equals(nodeInfo.getParent().getParent().getViewIdResourceName(), "primary-question-wrap"))) {
                    String question = nodeInfo.getText().toString().replaceAll("\\s", "");
                    if (TextUtils.equals(preQuestion, question)) {
                        return;
                    }
                    preQuestion = question;
                    try {
                        String str = jse.eval(question.replaceAll("×", "*").replaceAll("÷", "/").replaceAll("=", "")).toString();
                        if (str.endsWith(".0")) {
                            str = str.replace(".0", "");
                        } else {
                            Matcher matcher = pattern.matcher(str);
                            if (matcher.find()) {
                                int roundNum = matcher.start() - str.indexOf(".") - 1;
                                str = String.format("%." + roundNum + "f", Double.parseDouble(str));
                            }
                        }
                        Log.i("LinGH", str);
                        List<Path> paths = getPathByStr(str);
                        GestureDescription.Builder builder = new GestureDescription.Builder();
                        for (int i = 0; i < paths.size(); i++) {
                            builder.addStroke(new GestureDescription.StrokeDescription(paths.get(i), 170L * i, 150));
                        }
                        service.dispatchGesture(builder.build(), new AccessibilityService.GestureResultCallback() {
                            private final String str = preQuestion;

                            @Override
                            public void onCompleted(GestureDescription gestureDescription) {
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (TextUtils.equals(preQuestion, str)) {
                                            preQuestion = null;
                                        }
                                        xiaoYuanKouSuan(service.getRootInActiveWindow());
                                    }
                                }, 1000);
                            }
                        }, null);
                    } catch (ScriptException e) {
                        throw new RuntimeException(e);
                    }

                }
            }
        }
    }

    private List<AccessibilityNodeInfo> findByText(List<AccessibilityNodeInfo> nodeInfoList, String str) {
        return nodeInfoList.stream().filter(e -> e.getText() != null && e.getText().toString().contains(str)).collect(Collectors.toList());
    }

    public void onConfigurationChanged(Configuration newConfig) {
        if (addDataBinding != null && viewClickPosition != null && widgetSelectBinding != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            aParams.x = (metrics.widthPixels - aParams.width) / 2;
            aParams.y = metrics.heightPixels - aParams.height;
            bParams.width = metrics.widthPixels;
            bParams.height = metrics.heightPixels;
            cParams.x = (metrics.widthPixels - cParams.width) / 2;
            cParams.y = (metrics.heightPixels - cParams.height) / 2;
            windowManager.updateViewLayout(addDataBinding.getRoot(), aParams);
            windowManager.updateViewLayout(viewClickPosition, cParams);
            widgetSelectBinding.frame.removeAllViews();
            TextView text = new TextView(service);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            text.setGravity(Gravity.CENTER);
            text.setTextColor(0xffff0000);
            text.setText("请重新刷新布局");
            widgetSelectBinding.frame.addView(text, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            windowManager.updateViewLayout(widgetSelectBinding.frame, bParams);
        }
        if (dbClickView != null) {
            Rect rect = MyUtils.getDbClickPosition();
            dbClickLp.x = rect.left;
            dbClickLp.y = rect.top;
            dbClickLp.width = rect.width();
            dbClickLp.height = rect.height();
            windowManager.updateViewLayout(dbClickView, dbClickLp);
        }
    }

    public boolean onUnbind(Intent intent) {
        service.unregisterReceiver(myBroadcastReceiver);
        service.unregisterReceiver(myPackageReceiver);
        return true;
    }

    /**
     * 将数据暴露给其他组件
     * 方便修改
     */
    public Map<String, AppDescribe> getAppDescribeMap() {
        return appDescribeMap;
    }

    /**
     * 查找并点击View
     */
    private void findAndClickView(List<AccessibilityNodeInfo> nodeInfoList, Set<Widget> widgets) {
        LinkedList<AccessibilityNodeInfo> list = new LinkedList<>(nodeInfoList);
        while (!list.isEmpty() && onOffWidgetSub) {
            AccessibilityNodeInfo nodeInfo = list.poll();
            if (nodeInfo != null) {
                clickByWidget(nodeInfo, widgets);
                for (int n = 0; n < nodeInfo.getChildCount(); n++) {
                    list.add(nodeInfo.getChild(n));
                }
            }
        }
    }

    private void clickByWidget(AccessibilityNodeInfo nodeInfo, Set<Widget> widgets) {
        final Rect temRect = new Rect();
        nodeInfo.getBoundsInScreen(temRect);
        CharSequence cId = nodeInfo.getViewIdResourceName();
        CharSequence cDescribe = nodeInfo.getContentDescription();
        CharSequence cText = nodeInfo.getText();
        for (Widget e : widgets) {
            if (e.condition == Widget.CONDITION_OR) {
                if (temRect.equals(e.widgetRect)) {
                    addLog(String.format("找到控件：Bonus[%s]", gsonNoPretty.toJson(e.widgetRect)));
                } else if (cId != null && !e.widgetId.isEmpty() && cId.toString().equals(e.widgetId)) {
                    addLog(String.format("找到控件：Id[%s]", e.widgetId));
                } else if (cDescribe != null && !e.widgetDescribe.isEmpty() && cDescribe.toString().matches(e.widgetDescribe)) {
                    addLog(String.format("找到控件：Describe[%s]", e.widgetDescribe));
                } else if (cText != null && !e.widgetText.isEmpty() && cText.toString().matches(e.widgetText)) {
                    addLog(String.format("找到控件：Text[%s]", e.widgetText));
                } else {
                    continue;
                }
            } else if (e.condition == Widget.CONDITION_AND) {
                StringBuilder stringBuilder = new StringBuilder();
                if (e.widgetRect != null) {
                    if (!temRect.equals(e.widgetRect)) {
                        continue;
                    } else {
                        stringBuilder.append(String.format(", Bonus[%s]", gsonNoPretty.toJson(e.widgetRect)));
                    }
                }
                if (cId != null && !e.widgetId.isEmpty()) {
                    if (!cId.toString().equals(e.widgetId)) {
                        continue;
                    } else {
                        stringBuilder.append(String.format(", Id[%s]", e.widgetId));
                    }
                }
                if (cDescribe != null && !e.widgetDescribe.isEmpty()) {
                    if (!cDescribe.toString().matches(e.widgetDescribe)) {
                        continue;
                    } else {
                        stringBuilder.append(String.format(", Describe[%s]", e.widgetDescribe));
                    }
                }
                if (cText != null && !e.widgetText.isEmpty()) {
                    if (!cText.toString().matches(e.widgetText)) {
                        continue;
                    } else {
                        stringBuilder.append(String.format(", Text[%s]", e.widgetText));
                    }
                }
                if (e.widgetRect == null
                        && (cId == null || e.widgetId.isEmpty())
                        && (cDescribe == null || e.widgetDescribe.isEmpty())
                        && (cText == null || e.widgetText.isEmpty())) {
                    continue;
                }
                addLog(String.format("找到控件：%s", stringBuilder.substring(2)));
            } else {
                continue;
            }
            if (e.action == Widget.ACTION_CLICK) {
                if (!e.noRepeat || alreadyClickSet.add(e)) {
                    executorServiceSub.schedule(new Runnable() {
                        @Override
                        public void run() {
                            if (!debounceSet.add(e)) {
                                return;
                            }
                            executorServiceSub.schedule(new Runnable() {
                                @Override
                                public void run() {
                                    debounceSet.remove(e);
                                }
                            }, e.debounceDelay, TimeUnit.MILLISECONDS);

                            executorServiceSub.scheduleWithFixedDelay(new Runnable() {
                                private int clickNumber = 0;

                                @Override
                                public void run() {
                                    if (!onOffWidgetSub || !nodeInfo.refresh()) {
                                        throw new RuntimeException();
                                    }
                                    if (clickNumber++ >= e.clickNumber) {
                                        throw new RuntimeException();
                                    }
                                    if (e.clickOnly) {
                                        click(temRect.centerX(), temRect.centerY());
                                    } else if (!nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                        click(temRect.centerX(), temRect.centerY());
                                    }
                                    if (clickNumber == 1) {
                                        e.triggerCount += 1;
                                        e.lastTriggerTime = System.currentTimeMillis();
                                        dataDao.updateWidget(e);
                                        showToast(e, e.toast);
                                        addLog("点击控件：" + gson.toJson(e));
                                        if (alreadyClickSet.size() >= widgets.size()) {
                                            serviceInfo.eventTypes &= ~AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
                                            service.setServiceInfo(serviceInfo);
                                        }
                                    }
                                }
                            }, 0, e.clickInterval, TimeUnit.MILLISECONDS);
                        }
                    }, e.clickDelay, TimeUnit.MILLISECONDS);
                }
            } else if (e.action == Widget.ACTION_BACK) {
                if (alreadyClickSet.add(e)) {
                    if (onOffWidgetSub && nodeInfo.refresh()) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                        e.triggerCount += 1;
                        e.lastTriggerTime = System.currentTimeMillis();
                        dataDao.updateWidget(e);
                        showToast(e, e.toast);
                        addLog("执行返回：" + gson.toJson(e));
                        if (alreadyClickSet.size() >= widgets.size()) {
                            serviceInfo.eventTypes &= ~AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
                            service.setServiceInfo(serviceInfo);
                        }
                    }
                }
            }
            break;
        }
    }

    /**
     * 查找所有
     * 的控件
     */
    private ArrayList<AccessibilityNodeInfo> findAllNode(List<AccessibilityNodeInfo> root) {
        LinkedList<AccessibilityNodeInfo> listA = new LinkedList<>(root);
        HashSet<AccessibilityNodeInfo> setR = new HashSet<>();
        while (!listA.isEmpty()) {
            final AccessibilityNodeInfo node = listA.poll();
            if (node == null) {
                continue;
            }
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (rect.width() <= 0 || rect.height() <= 0) {
                continue;
            }
            for (int n = 0; n < node.getChildCount(); n++) {
                listA.add(node.getChild(n));
            }
            setR.add(node);
        }
        ArrayList<AccessibilityNodeInfo> listR = new ArrayList<>(setR);
        listR.sort(new Comparator<AccessibilityNodeInfo>() {
            @Override
            public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                Rect rectA = new Rect();
                Rect rectB = new Rect();
                a.getBoundsInScreen(rectA);
                b.getBoundsInScreen(rectB);
                return rectB.width() * rectB.height() - rectA.width() * rectA.height();
            }
        });
        return listR;
    }

    /**
     * 模拟
     * 点击
     */
    private boolean click(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        return service.dispatchGesture(builder.build(), null, null);
    }

    /**
     * android 7.0 以上
     * 避免无障碍服务冲突
     */
    public void closeService() {
        service.disableSelf();
    }

    /**
     * 开启无障碍服务时调用
     * 获取运行时需要的数据
     */
    private void getRunningData() {
        Set<String> pkgNormalSet = new HashSet<>();
        Set<String> pkgOffSet = new HashSet<>();
        Set<String> pkgOnSet = new HashSet<>();
        //所有已安装的应用
        Set<String> pkgInstalledSet = packageManager
                .getInstalledPackages(PackageManager.GET_META_DATA)
                .stream()
                .map(e -> e.packageName)
                .collect(Collectors.toSet());
        pkgNormalSet.addAll(pkgInstalledSet);
        //输入法、桌面、本应用需要默认关闭
        Set<String> pkgInputMethodSet = inputMethodManager
                .getInputMethodList()
                .stream()
                .map(InputMethodInfo::getPackageName)
                .collect(Collectors.toSet());
        Set<String> pkgHasHomeSet = packageManager
                .queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
                .stream()
                .map(e -> e.activityInfo.packageName)
                .collect(Collectors.toSet());
        pkgOffSet.addAll(pkgInputMethodSet);
        pkgOffSet.addAll(pkgHasHomeSet);
        pkgOffSet.add(service.getPackageName());
        //MIUI系统自带的广告服务app，需要开启
        pkgOnSet.add("com.miui.systemAdSolution");

        List<AppDescribe> appDescribeList = new ArrayList<>();
        for (String e : pkgNormalSet) {
            try {
                ApplicationInfo info = packageManager.getApplicationInfo(e, PackageManager.GET_META_DATA);
                AppDescribe appDescribe = new AppDescribe();
                appDescribe.appName = packageManager.getApplicationLabel(info).toString();
                appDescribe.appPackage = info.packageName;
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == ApplicationInfo.FLAG_SYSTEM || pkgOffSet.contains(info.packageName)) {
                    appDescribe.coordinateOnOff = false;
                    appDescribe.widgetOnOff = false;
                }
                if (pkgOnSet.contains(info.packageName)) {
                    appDescribe.coordinateOnOff = false;
                    appDescribe.widgetOnOff = false;
                }
                appDescribeList.add(appDescribe);
            } catch (PackageManager.NameNotFoundException exception) {
                // exception.printStackTrace();
            }
        }
        dataDao.insertAppDescribe(appDescribeList);
        for (String e : pkgNormalSet) {
            AppDescribe appDescribeTmp = dataDao.getAppDescribeByPackage(e);
            appDescribeTmp.getOtherFieldsFromDatabase(dataDao);
            appDescribeMap.put(e, appDescribeTmp);
        }
    }

    /**
     * 创建规则时调用
     */
    @SuppressLint("ClickableViewAccessibility")
    public void showAddDataWindow(boolean capture) {
        if (pkgSuggestNotOnList == null) {
            Set<String> pkgSysSet = packageManager
                    .getInstalledPackages(PackageManager.MATCH_SYSTEM_ONLY)
                    .stream().map(e -> e.packageName)
                    .collect(Collectors.toSet());
            Set<String> pkgInputMethodSet = inputMethodManager
                    .getInputMethodList()
                    .stream()
                    .map(InputMethodInfo::getPackageName)
                    .collect(Collectors.toSet());
            Set<String> pkgHasHomeSet = packageManager
                    .queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
                    .stream()
                    .map(e -> e.activityInfo.packageName)
                    .collect(Collectors.toSet());
            pkgSuggestNotOnList = new HashSet<>();
            pkgSuggestNotOnList.addAll(pkgSysSet);
            pkgSuggestNotOnList.addAll(pkgInputMethodSet);
            pkgSuggestNotOnList.addAll(pkgHasHomeSet);
        }
        if (viewClickPosition != null || addDataBinding != null || widgetSelectBinding != null) {
            return;
        }
        final Widget widgetSelect = new Widget();
        final Coordinate coordinateSelect = new Coordinate();
        final LayoutInflater inflater = LayoutInflater.from(service);

        addDataBinding = ViewAddDataBinding.inflate(inflater);
        widgetSelectBinding = ViewWidgetSelectBinding.inflate(inflater);

        viewClickPosition = new ImageView(service);
        viewClickPosition.setImageResource(R.drawable.p);

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        int width = Math.min(metrics.heightPixels, metrics.widthPixels);
        int height = Math.max(metrics.heightPixels, metrics.widthPixels);

        aParams = new WindowManager.LayoutParams();
        aParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        aParams.format = PixelFormat.TRANSPARENT;
        aParams.gravity = Gravity.START | Gravity.TOP;
        aParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        aParams.width = width;
        aParams.height = height / 5;
        aParams.x = (metrics.widthPixels - aParams.width) / 2;
        aParams.y = metrics.heightPixels / 5 * 3;
        aParams.alpha = 0.9f;

        bParams = new WindowManager.LayoutParams();
        bParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        bParams.format = PixelFormat.TRANSPARENT;
        bParams.gravity = Gravity.START | Gravity.TOP;
        bParams.width = metrics.widthPixels;
        bParams.height = metrics.heightPixels;
        bParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        bParams.alpha = 0f;

        cParams = new WindowManager.LayoutParams();
        cParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        cParams.format = PixelFormat.TRANSPARENT;
        cParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        cParams.gravity = Gravity.START | Gravity.TOP;
        cParams.width = cParams.height = width / 4;
        cParams.x = (metrics.widthPixels - cParams.width) / 2;
        cParams.y = (metrics.heightPixels - cParams.height) / 2;
        cParams.alpha = 0f;

        addDataBinding.getRoot().setOnTouchListener(new View.OnTouchListener() {
            int startRowX = 0, startRowY = 0, startLpX = 0, startLpY = 0;
            ScheduledFuture<?> future = executorServiceSub.schedule(new Runnable() {
                @Override
                public void run() {
                }
            }, 0, TimeUnit.MILLISECONDS);

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                addDataBinding.getRoot().post(new Runnable() {
                    @Override
                    public void run() {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                startRowX = Math.round(event.getRawX());
                                startRowY = Math.round(event.getRawY());
                                startLpX = aParams.x;
                                startLpY = aParams.y;
                                future = executorServiceSub.schedule(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (Math.abs(aParams.x - startLpX) < 10 && Math.abs(aParams.y - startLpY) < 10) {
                                            Matcher matcher = Pattern.compile("(\\w|\\.)+").matcher(addDataBinding.pacName.getText().toString());
                                            if (matcher.find()) {
                                                if (appDescribeMap.containsKey(matcher.group())) {
                                                    Intent intent = new Intent(service, EditDataActivity.class);
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                                    intent.putExtra("packageName", matcher.group());
                                                    service.startActivity(intent);
                                                }
                                            }
                                        }
                                    }
                                }, 1000, TimeUnit.MILLISECONDS);
                                break;
                            case MotionEvent.ACTION_MOVE:
                                aParams.x = startLpX + (Math.round(event.getRawX()) - startRowX);
                                aParams.y = startLpY + (Math.round(event.getRawY()) - startRowY);
                                windowManager.updateViewLayout(addDataBinding.getRoot(), aParams);
                                break;
                            case MotionEvent.ACTION_UP:
                                DisplayMetrics metrics = new DisplayMetrics();
                                windowManager.getDefaultDisplay().getRealMetrics(metrics);
                                aParams.x = Math.max(aParams.x, 0);
                                aParams.x = Math.min(aParams.x, metrics.widthPixels - aParams.width);
                                aParams.y = Math.max(aParams.y, 0);
                                aParams.y = Math.min(aParams.y, metrics.heightPixels - aParams.height);
                                windowManager.updateViewLayout(addDataBinding.getRoot(), aParams);
                                future.cancel(false);
                                break;
                        }
                    }
                });
                return true;
            }
        });
        viewClickPosition.setOnTouchListener(new View.OnTouchListener() {
            final int width = cParams.width / 2;
            final int height = cParams.height / 2;
            int startRowX = 0, startRowY = 0, startLpX = 0, startLpY = 0;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                viewClickPosition.post(new Runnable() {
                    @Override
                    public void run() {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                addDataBinding.saveAim.setEnabled(appDescribeMap.containsKey(currentPackage));
                                cParams.alpha = 0.9f;
                                windowManager.updateViewLayout(viewClickPosition, cParams);
                                startRowX = Math.round(event.getRawX());
                                startRowY = Math.round(event.getRawY());
                                startLpX = cParams.x;
                                startLpY = cParams.y;
                                break;
                            case MotionEvent.ACTION_MOVE:
                                cParams.x = startLpX + (Math.round(event.getRawX()) - startRowX);
                                cParams.y = startLpY + (Math.round(event.getRawY()) - startRowY);
                                windowManager.updateViewLayout(viewClickPosition, cParams);
                                coordinateSelect.appPackage = currentPackage;
                                coordinateSelect.appActivity = currentActivity;
                                coordinateSelect.xPosition = cParams.x + width;
                                coordinateSelect.yPosition = cParams.y + height;
                                addDataBinding.pacName.setText(coordinateSelect.appPackage);
                                addDataBinding.actName.setText(coordinateSelect.appActivity);
                                addDataBinding.xy.setText("X轴：" + String.format("%-4d", coordinateSelect.xPosition) + "    " + "Y轴：" + String.format("%-4d", coordinateSelect.yPosition));
                                break;
                            case MotionEvent.ACTION_UP:
                                cParams.alpha = 0.5f;
                                windowManager.updateViewLayout(viewClickPosition, cParams);
                                break;
                        }
                    }
                });
                return true;
            }
        });
        addDataBinding.switchWid.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bParams.alpha == 0) {
                    executorServiceMain.execute(new Runnable() {
                        @Override
                        public void run() {
                            AccessibilityNodeInfo root = service.getRootInActiveWindow();
                            if (root == null) {
                                return;
                            }
                            List<AccessibilityWindowInfo> windowInfoList = service.getWindows();
                            Collections.reverse(windowInfoList);
                            if (windowInfoList.isEmpty()) {
                                return;
                            }
                            ArrayList<AccessibilityNodeInfo> nodeList = new ArrayList<>();
                            for (AccessibilityWindowInfo windowInfo : windowInfoList) {
                                AccessibilityNodeInfo nodeInfo = windowInfo.getRoot();
                                if (TextUtils.equals(nodeInfo.getPackageName(), root.getPackageName())) {
                                    nodeList.addAll(findAllNode(Collections.singletonList(nodeInfo)));
                                }
                            }
                            if (nodeList.isEmpty()) {
                                return;
                            }
                            addDataBinding.switchWid.post(new Runnable() {
                                @Override
                                public void run() {
                                    View.OnClickListener onClickListener = new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            v.requestFocus();
                                        }
                                    };
                                    View.OnFocusChangeListener onFocusChangeListener = new View.OnFocusChangeListener() {
                                        @Override
                                        public void onFocusChange(View v, boolean hasFocus) {
                                            if (hasFocus) {
                                                AccessibilityNodeInfo nodeInfo = (AccessibilityNodeInfo) v.getTag(R.string.nodeInfo);
                                                Rect rect = (Rect) v.getTag(R.string.rect);
                                                widgetSelect.widgetRect = rect;
                                                widgetSelect.widgetClickable = nodeInfo.isClickable();
                                                CharSequence cId = nodeInfo.getViewIdResourceName();
                                                widgetSelect.widgetId = cId == null ? "" : cId.toString();
                                                CharSequence cDesc = nodeInfo.getContentDescription();
                                                widgetSelect.widgetDescribe = cDesc == null ? "" : cDesc.toString();
                                                CharSequence cText = nodeInfo.getText();
                                                widgetSelect.widgetText = cText == null ? "" : cText.toString();
                                                addDataBinding.saveWid.setEnabled(appDescribeMap.containsKey(currentPackage));
                                                addDataBinding.pacName.setText(widgetSelect.appPackage);
                                                addDataBinding.actName.setText(widgetSelect.appActivity);
                                                String click = nodeInfo.isClickable() ? "true" : "false";
                                                String bonus = rect.toShortString();
                                                String id = cId == null || !cId.toString().contains(":id/") ? "" : cId.toString().substring(cId.toString().indexOf(":id/") + 4);
                                                String desc = cDesc == null ? "" : cDesc.toString();
                                                String text = cText == null ? "" : cText.toString();
                                                addDataBinding.widget.setText("click:" + click + " " + "bonus:" + bonus + (id.isEmpty() ? "" : " " + "id:" + id) + (desc.isEmpty() ? "" : " " + "desc:" + desc) + (text.isEmpty() ? "" : " " + "text:" + text));
                                                v.setBackgroundResource(R.drawable.node_focus);
                                            } else {
                                                v.setBackgroundResource(R.drawable.node);
                                            }
                                        }
                                    };
                                    for (AccessibilityNodeInfo nodeInfo : nodeList) {
                                        Rect rect = new Rect();
                                        nodeInfo.getBoundsInScreen(rect);
                                        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(rect.width(), rect.height());
                                        params.leftMargin = rect.left;
                                        params.topMargin = rect.top;
                                        View view = new View(service);
                                        view.setBackgroundResource(R.drawable.node);
                                        view.setFocusableInTouchMode(true);
                                        view.setFocusable(true);
                                        view.setOnClickListener(onClickListener);
                                        view.setOnFocusChangeListener(onFocusChangeListener);
                                        view.setTag(R.string.nodeInfo, nodeInfo);
                                        view.setTag(R.string.rect, rect);
                                        widgetSelectBinding.frame.addView(view, params);
                                    }
                                    bParams.alpha = 0.5f;
                                    bParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                                    windowManager.updateViewLayout(widgetSelectBinding.getRoot(), bParams);
                                    widgetSelect.appPackage = currentPackage;
                                    widgetSelect.appActivity = currentActivity;
                                    addDataBinding.pacName.setText(widgetSelect.appPackage);
                                    addDataBinding.actName.setText(widgetSelect.appActivity);
                                    addDataBinding.switchWid.setText("隐藏布局");
                                }
                            });
                        }
                    });
                } else {
                    bParams.alpha = 0f;
                    bParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    windowManager.updateViewLayout(widgetSelectBinding.getRoot(), bParams);
                    addDataBinding.saveWid.setEnabled(false);
                    widgetSelectBinding.frame.removeAllViews();
                    addDataBinding.switchWid.setText("显示布局");
                }
            }
        });
        addDataBinding.switchAim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Button button = (Button) v;
                if (cParams.alpha == 0) {
                    coordinateSelect.appPackage = currentPackage;
                    coordinateSelect.appActivity = currentActivity;
                    cParams.alpha = 0.5f;
                    cParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    windowManager.updateViewLayout(viewClickPosition, cParams);
                    addDataBinding.pacName.setText(coordinateSelect.appPackage);
                    addDataBinding.actName.setText(coordinateSelect.appActivity);
                    button.setText("隐藏准星");
                } else {
                    cParams.alpha = 0f;
                    cParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    windowManager.updateViewLayout(viewClickPosition, cParams);
                    addDataBinding.saveAim.setEnabled(false);
                    button.setText("显示准星");
                }
            }
        });
        addDataBinding.saveWid.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        AppDescribe temAppDescribe = appDescribeMap.get(widgetSelect.appPackage);
                        if (temAppDescribe != null) {
                            Widget temWidget = new Widget(widgetSelect);
                            temWidget.createTime = System.currentTimeMillis();
                            dataDao.insertWidget(temWidget);
                            addDataBinding.saveWid.setEnabled(false);
                            addDataBinding.pacName.setText(widgetSelect.appPackage + " (以下控件数据已保存)");
                            temAppDescribe.getWidgetFromDatabase(dataDao);
                            if (!temAppDescribe.widgetOnOff) {
                                showWarningDialog(new Runnable() {
                                    @Override
                                    public void run() {
                                        temAppDescribe.widgetOnOff = true;
                                        dataDao.updateAppDescribe(temAppDescribe);
                                    }
                                }, service.getString(R.string.widgetOffWarning));
                            }
                        }
                    }
                };
                if (pkgSuggestNotOnList.contains(widgetSelect.appPackage)) {
                    showWarningDialog(runnable, service.getString(R.string.addWarning));
                } else {
                    runnable.run();
                }
            }
        });
        addDataBinding.saveAim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        AppDescribe temAppDescribe = appDescribeMap.get(coordinateSelect.appPackage);
                        if (temAppDescribe != null) {
                            Coordinate temCoordinate = new Coordinate(coordinateSelect);
                            temCoordinate.createTime = System.currentTimeMillis();
                            dataDao.insertCoordinate(temCoordinate);
                            addDataBinding.saveAim.setEnabled(false);
                            addDataBinding.pacName.setText(coordinateSelect.appPackage + " (以下坐标数据已保存)");
                            temAppDescribe.getCoordinateFromDatabase(dataDao);
                            if (!temAppDescribe.coordinateOnOff) {
                                showWarningDialog(new Runnable() {
                                    @Override
                                    public void run() {
                                        temAppDescribe.coordinateOnOff = true;
                                        dataDao.updateAppDescribe(temAppDescribe);
                                    }
                                }, service.getString(R.string.coordinateOffWarning));
                            }
                        }
                    }
                };
                if (pkgSuggestNotOnList.contains(coordinateSelect.appPackage)) {
                    showWarningDialog(runnable, service.getString(R.string.addWarning));
                } else {
                    runnable.run();
                }
            }
        });
        addDataBinding.quit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                windowManager.removeViewImmediate(widgetSelectBinding.getRoot());
                windowManager.removeViewImmediate(addDataBinding.getRoot());
                windowManager.removeViewImmediate(viewClickPosition);
                pkgSuggestNotOnList = null;
                widgetSelectBinding = null;
                addDataBinding = null;
                viewClickPosition = null;
            }
        });
        windowManager.addView(widgetSelectBinding.getRoot(), bParams);
        windowManager.addView(addDataBinding.getRoot(), aParams);
        windowManager.addView(viewClickPosition, cParams);

        if (capture) {
            addDataBinding.switchWid.callOnClick();
        }
    }

    private void showWarningDialog(Runnable onSureRun, String message) {
        String prePackage = currentPackage;
        String preActivity = currentActivity;
        ViewDialogWarningBinding binding = ViewDialogWarningBinding.inflate(LayoutInflater.from(service));
        binding.message.setText(message);
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(service);
        alertDialogBuilder.setView(binding.getRoot());
        alertDialogBuilder.setNegativeButton("取消", null);
        alertDialogBuilder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                onSureRun.run();
            }
        });
        alertDialogBuilder.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                currentPackage = prePackage;
                currentActivity = preActivity;
            }
        });
        AlertDialog dialog = alertDialogBuilder.create();
        dialog.getWindow().getAttributes().type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        dialog.show();
    }

    public void keepAliveByNotification(boolean enable) {
        if (enable) {
            NotificationManager notificationManager = service.getSystemService(NotificationManager.class);
            Intent intent = new Intent(service, MainActivity.class);
            Notification.Builder builder = new Notification.Builder(service);
            builder.setOngoing(true);
            builder.setAutoCancel(false);
            builder.setSmallIcon(R.drawable.app);
            builder.setContentTitle(service.getText(R.string.appName));
            builder.setContentText("运行中");
            builder.setContentIntent(PendingIntent.getActivity(service, 0x01, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setChannelId(service.getPackageName());
                NotificationChannel channel = new NotificationChannel(service.getPackageName(), service.getString(R.string.appName), NotificationManager.IMPORTANCE_HIGH);
                notificationManager.createNotificationChannel(channel);
            }
            service.startForeground(0x01, builder.build());
        } else {
            service.stopForeground(true);
        }
    }

    public void keepAliveByFloatingWindow(boolean enable) {
        if (enable && ignoreView == null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            lp.gravity = Gravity.START | Gravity.TOP;
            lp.format = PixelFormat.TRANSPARENT;
            lp.alpha = 0;
            lp.width = 0;
            lp.height = 0;
            lp.x = 0;
            lp.y = 0;
            ignoreView = new View(service);
            ignoreView.setBackgroundColor(Color.TRANSPARENT);
            windowManager.addView(ignoreView, lp);
        } else if (ignoreView != null) {
            windowManager.removeView(ignoreView);
            ignoreView = null;
        }
    }

    public void showDbClickFloating(boolean enable) {
        if (enable && dbClickView == null) {
            dbClickLp = new WindowManager.LayoutParams();
            dbClickLp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            dbClickLp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            dbClickLp.gravity = Gravity.START | Gravity.TOP;
            dbClickLp.format = PixelFormat.TRANSPARENT;
            dbClickLp.alpha = 0.5f;
            Rect rect = MyUtils.getDbClickPosition();
            dbClickLp.x = rect.left;
            dbClickLp.y = rect.top;
            dbClickLp.width = rect.width();
            dbClickLp.height = rect.height();

            dbClickView = new View(service);
            dbClickView.setBackgroundColor(Color.TRANSPARENT);
            dbClickView.setOnClickListener(new View.OnClickListener() {
                private long previousTime = 0;

                @Override
                public void onClick(View v) {
                    long currentTime = System.currentTimeMillis();
                    long interval = currentTime - previousTime;
                    previousTime = currentTime;
                    if (interval <= 1000) {
                        showAddDataWindow(true);
                    }
                }
            });
            windowManager.addView(dbClickView, dbClickLp);
        } else if (dbClickView != null) {
            windowManager.removeView(dbClickView);
            dbClickView = null;
        }
    }

    public void showDbClickSetting() {
        if (dbClickView == null || dbClickLp == null) {
            return;
        }
        ViewDbClickSettingBinding dbClickSettingBinding = ViewDbClickSettingBinding.inflate(LayoutInflater.from(service));
        AlertDialog alertDialog = new AlertDialog.Builder(service).setView(dbClickSettingBinding.getRoot()).create();
        alertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        alertDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        alertDialog.show();
        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(displayMetrics);
        WindowManager.LayoutParams lp = alertDialog.getWindow().getAttributes();
        lp.width = displayMetrics.widthPixels / 5 * 4;
        lp.height = displayMetrics.heightPixels / 3;
        alertDialog.onWindowAttributesChanged(lp);

        dbClickSettingBinding.seekBarW.setMax(displayMetrics.widthPixels / 2);
        dbClickSettingBinding.seekBarH.setMax(displayMetrics.heightPixels / 4);
        dbClickSettingBinding.seekBarX.setMax(displayMetrics.widthPixels);
        dbClickSettingBinding.seekBarY.setMax(displayMetrics.heightPixels);
        dbClickSettingBinding.seekBarW.setProgress(dbClickLp.width);
        dbClickSettingBinding.seekBarH.setProgress(dbClickLp.height);
        dbClickSettingBinding.seekBarX.setProgress(dbClickLp.x);
        dbClickSettingBinding.seekBarY.setProgress(dbClickLp.y);
        SeekBar.OnSeekBarChangeListener onSeekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                if (seekBar == dbClickSettingBinding.seekBarW) {
                    dbClickLp.width = i;
                }
                if (seekBar == dbClickSettingBinding.seekBarH) {
                    dbClickLp.height = i;
                }
                if (seekBar == dbClickSettingBinding.seekBarX) {
                    dbClickLp.x = i;
                }
                if (seekBar == dbClickSettingBinding.seekBarY) {
                    dbClickLp.y = i;
                }
                windowManager.updateViewLayout(dbClickView, dbClickLp);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Rect rect = new Rect();
                rect.left = dbClickLp.x;
                rect.top = dbClickLp.y;
                rect.right = dbClickLp.x + dbClickLp.width;
                rect.bottom = dbClickLp.y + dbClickLp.height;
                MyUtils.setDbClickPosition(rect);
            }
        };
        dbClickSettingBinding.seekBarW.setOnSeekBarChangeListener(onSeekBarChangeListener);
        dbClickSettingBinding.seekBarH.setOnSeekBarChangeListener(onSeekBarChangeListener);
        dbClickSettingBinding.seekBarX.setOnSeekBarChangeListener(onSeekBarChangeListener);
        dbClickSettingBinding.seekBarY.setOnSeekBarChangeListener(onSeekBarChangeListener);
        alertDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                dbClickView.setBackgroundColor(Color.TRANSPARENT);
                windowManager.updateViewLayout(dbClickView, dbClickLp);
            }
        });
        dbClickView.setBackgroundColor(Color.RED);
        windowManager.updateViewLayout(dbClickView, dbClickLp);
    }

    private void addLog(String log) {
        logList.add(simpleDateFormat.format(new Date()) + " " + log);
        if (logList.size() > 1000) {
            logList.poll();
        }
    }

    public String getLog() {
        StringBuilder stringBuilder = new StringBuilder();
        for (String s : logList) {
            stringBuilder.append(s).append("\n");
        }
        return stringBuilder.toString();
    }

    public class MyBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (TextUtils.equals(intent.getAction(), Intent.ACTION_SCREEN_OFF)) {
                currentPackageSub = isScreenOffPre;
                currentActivity = isScreenOffPre;
            }
        }
    }

    public class MyPackageReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (TextUtils.equals(intent.getAction(), Intent.ACTION_PACKAGE_ADDED)) {
                String dataString = intent.getDataString();
                String packageName = dataString != null ? dataString.substring(8) : null;
                if (!TextUtils.isEmpty(packageName)) {
                    executorServiceSub.schedule(new Runnable() {
                        @Override
                        public void run() {
                            AppDescribe appDescribe = dataDao.getAppDescribeByPackage(packageName);
                            if (appDescribe == null) {
                                appDescribe = new AppDescribe();
                                try {
                                    ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
                                    appDescribe.appName = packageManager.getApplicationLabel(applicationInfo).toString();
                                } catch (PackageManager.NameNotFoundException e) {
                                    appDescribe.appName = "unknown";
                                    // e.printStackTrace();
                                }
                                appDescribe.appPackage = packageName;
                                List<ResolveInfo> homeLaunchList = packageManager.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL);
                                for (ResolveInfo e : homeLaunchList) {
                                    if (packageName.equals(e.activityInfo.packageName)) {
                                        appDescribe.widgetOnOff = false;
                                        appDescribe.coordinateOnOff = false;
                                        break;
                                    }
                                }
                                List<InputMethodInfo> inputMethodInfoList = inputMethodManager.getInputMethodList();
                                for (InputMethodInfo e : inputMethodInfoList) {
                                    if (packageName.equals(e.getPackageName())) {
                                        appDescribe.widgetOnOff = false;
                                        appDescribe.coordinateOnOff = false;
                                        break;
                                    }
                                }
                                dataDao.insertAppDescribe(appDescribe);
                            }
                            appDescribe.getOtherFieldsFromDatabase(dataDao);
                            appDescribeMap.put(appDescribe.appPackage, appDescribe);
                        }
                    }, 2000, TimeUnit.MILLISECONDS);
                }
            }
            if (TextUtils.equals(intent.getAction(), Intent.ACTION_PACKAGE_FULLY_REMOVED)) {
                String dataString = intent.getDataString();
                String packageName = dataString != null ? dataString.substring(8) : null;
                if (!TextUtils.isEmpty(packageName)) {
                    appDescribeMap.remove(packageName);
                }
            }
        }
    }

    private void showToast(Object trigger, String content) {
        if (trigger == null || trigger == preTrigger) {
            return;
        }
        if (TextUtils.isEmpty(content)) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(service, content, Toast.LENGTH_SHORT).show();
                preTrigger = trigger;
            }
        });
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                preTrigger = null;
            }
        }, 1000);
    }
}
