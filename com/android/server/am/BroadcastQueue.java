/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.am;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.ApplicationThreadNative;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.EventLog;
import android.util.EventLogTags;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.server.DeviceIdleController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST_LIGHT;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_MU;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_BROADCAST;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_MU;

/**
 * BROADCASTS
 * <p>
 * We keep two broadcast queues and associated bookkeeping（记账）, one for those at
 * foreground priority, and one for normal (background-priority) broadcasts.
 */
public final class BroadcastQueue {
    private static final String TAG = "BroadcastQueue";
    private static final String TAG_MU = TAG + POSTFIX_MU;
    private static final String TAG_BROADCAST = TAG + POSTFIX_BROADCAST;

    static final int MAX_BROADCAST_HISTORY = ActivityManager.isLowRamDeviceStatic() ? 10 : 50;
    static final int MAX_BROADCAST_SUMMARY_HISTORY
            = ActivityManager.isLowRamDeviceStatic() ? 25 : 300;

    final ActivityManagerService mService;

    /**
     * Recognizable moniker for this queue
     */
    final String mQueueName;

    /**
     * Timeout period for this queue's broadcasts
     */
    final long mTimeoutPeriod;

    /**
     * If true, we can delay broadcasts while waiting services to finish in the previous
     * receiver's process.
     */
    final boolean mDelayBehindServices;

    /**
     * Lists of all active broadcasts that are to be executed immediately
     * (without waiting for another broadcast to finish).  Currently this only
     * contains broadcasts to registered receivers, to avoid spinning up
     * a bunch of processes to execute IntentReceiver components.  Background-
     * and foreground-priority broadcasts are queued separately.
     */
    final ArrayList<BroadcastRecord> mParallelBroadcasts = new ArrayList<>();

    /**
     * List of all active broadcasts that are to be executed one at a time.
     * The object at the top of the list is the currently activity broadcasts;
     * those after it are waiting for the top to finish.  As with parallel
     * broadcasts, separate background- and foreground-priority queues are
     * maintained.
     */
    final ArrayList<BroadcastRecord> mOrderedBroadcasts = new ArrayList<>();

    /**
     * Historical data of past broadcasts, for debugging.  This is a ring buffer
     * whose last element is at mHistoryNext.
     */
    final BroadcastRecord[] mBroadcastHistory = new BroadcastRecord[MAX_BROADCAST_HISTORY];
    int mHistoryNext = 0;

    /**
     * Summary of historical data of past broadcasts, for debugging.  This is a
     * ring buffer whose last element is at mSummaryHistoryNext.
     */
    final Intent[] mBroadcastSummaryHistory = new Intent[MAX_BROADCAST_SUMMARY_HISTORY];
    int mSummaryHistoryNext = 0;

    /**
     * Various milestone timestamps of entries in the mBroadcastSummaryHistory ring
     * buffer, also tracked via the mSummaryHistoryNext index.  These are all in wall
     * clock time, not elapsed.
     */
    final long[] mSummaryHistoryEnqueueTime = new long[MAX_BROADCAST_SUMMARY_HISTORY];
    final long[] mSummaryHistoryDispatchTime = new long[MAX_BROADCAST_SUMMARY_HISTORY];
    final long[] mSummaryHistoryFinishTime = new long[MAX_BROADCAST_SUMMARY_HISTORY];

    /**
     * Set when we current have a BROADCAST_INTENT_MSG in flight.
     * 正在等待处理该消息时设置为true
     */
    boolean mBroadcastsScheduled = false;

    /**
     * True if we have a pending（等待） unexpired（未到期的） BROADCAST_TIMEOUT_MSG posted to our handler.
     */
    boolean mPendingBroadcastTimeoutMessage;

    /**
     * Intent broadcasts that we have tried to start, but are
     * waiting for the application's process to be created.  We only
     * need one per scheduling class (instead of a list) because we always
     * process broadcasts one at a time, so no others can be started while
     * waiting for this one.
     */
    BroadcastRecord mPendingBroadcast = null;

    /**
     * The receiver index that is pending, to restart the broadcast if needed.
     */
    int mPendingBroadcastRecvIndex;

    static final int BROADCAST_INTENT_MSG = ActivityManagerService.FIRST_BROADCAST_QUEUE_MSG;
    static final int BROADCAST_TIMEOUT_MSG = ActivityManagerService.FIRST_BROADCAST_QUEUE_MSG + 1;
    static final int SCHEDULE_TEMP_WHITELIST_MSG
            = ActivityManagerService.FIRST_BROADCAST_QUEUE_MSG + 2;

    final BroadcastHandler mHandler;

    private final class BroadcastHandler extends Handler {
        public BroadcastHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case BROADCAST_INTENT_MSG: {
                    if (DEBUG_BROADCAST) Slog.v(
                            TAG_BROADCAST, "Received BROADCAST_INTENT_MSG");
                    processNextBroadcast(true);
                }
                break;
                case BROADCAST_TIMEOUT_MSG: {
                    synchronized (mService) {
                        broadcastTimeoutLocked(true);
                    }
                }
                break;
                case SCHEDULE_TEMP_WHITELIST_MSG: {
                    DeviceIdleController.LocalService dic = mService.mLocalDeviceIdleController;
                    if (dic != null) {
                        dic.addPowerSaveTempWhitelistAppDirect(UserHandle.getAppId(msg.arg1),
                                msg.arg2, true, (String) msg.obj);
                    }
                }
                break;
            }
        }
    }

    private final class AppNotResponding implements Runnable {
        private final ProcessRecord mApp;
        private final String mAnnotation;

        public AppNotResponding(ProcessRecord app, String annotation) {
            mApp = app;
            mAnnotation = annotation;
        }

        @Override
        public void run() {
            mService.mAppErrors.appNotResponding(mApp, null, null, false, mAnnotation);
        }
    }

    BroadcastQueue(ActivityManagerService service, Handler handler,
                   String name, long timeoutPeriod, boolean allowDelayBehindServices) {
        mService = service;
        mHandler = new BroadcastHandler(handler.getLooper());
        mQueueName = name;
        mTimeoutPeriod = timeoutPeriod;
        mDelayBehindServices = allowDelayBehindServices;
    }

    public boolean isPendingBroadcastProcessLocked(int pid) {
        return mPendingBroadcast != null && mPendingBroadcast.curApp.pid == pid;
    }

    public void enqueueParallelBroadcastLocked(BroadcastRecord r) {
        mParallelBroadcasts.add(r);
        r.enqueueClockTime = System.currentTimeMillis();
    }

    public void enqueueOrderedBroadcastLocked(BroadcastRecord r) {
        mOrderedBroadcasts.add(r);
        r.enqueueClockTime = System.currentTimeMillis();
    }

    // 检测广播列表对象mParallelBroadcasts中是否存在相同的广播，如果存在替换该广播
    public final boolean replaceParallelBroadcastLocked(BroadcastRecord r) {
        for (int i = mParallelBroadcasts.size() - 1; i >= 0; i--) {
            final Intent curIntent = mParallelBroadcasts.get(i).intent;
            if (r.intent.filterEquals(curIntent)) {
                if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                        "***** DROPPING PARALLEL ["
                                + mQueueName + "]: " + r.intent);
                mParallelBroadcasts.set(i, r);
                return true;
            }
        }
        return false;
    }

    public final boolean replaceOrderedBroadcastLocked(BroadcastRecord r) {
        for (int i = mOrderedBroadcasts.size() - 1; i > 0; i--) {
            if (r.intent.filterEquals(mOrderedBroadcasts.get(i).intent)) {
                if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                        "***** DROPPING ORDERED ["
                                + mQueueName + "]: " + r.intent);
                mOrderedBroadcasts.set(i, r);
                return true;
            }
        }
        return false;
    }

    // 这里只是将广播发送到接收者进程，需要一直等待接收者进程处理完广播后返回，AMS才能处理当前BroadcastRecord
    // 里面的下一个receiver，所以直接返回就行了，反正需要等待的。
    private final void processCurBroadcastLocked(BroadcastRecord r,
                                                 ProcessRecord app) throws RemoteException {
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                "Process cur broadcast " + r + " for app " + app);
        if (app.thread == null) {
            throw new RemoteException();
        }
        if (app.inFullBackup) {
            skipReceiverLocked(r);
            return;
        }

        // 将进程的相关信息写入当前BroadcastRecord中相关的接收者
        r.receiver = app.thread.asBinder();
        r.curApp = app;
        app.curReceiver = r;
        app.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_RECEIVER);
        mService.updateLruProcessLocked(app, false, null);
        mService.updateOomAdjLocked();

        // Tell the application to launch this receiver.
        r.intent.setComponent(r.curComponent);

        boolean started = false;
        try {
            if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST,
                    "Delivering to component " + r.curComponent
                            + ": " + r);
            mService.notifyPackageUse(r.intent.getComponent().getPackageName(),
                    PackageManager.NOTIFY_PACKAGE_USE_BROADCAST_RECEIVER);
            // 处理广播，等待接收进程的返回
            app.thread.scheduleReceiver(new Intent(r.intent), r.curReceiver,
                    mService.compatibilityInfoForPackageLocked(r.curReceiver.applicationInfo),
                    r.resultCode, r.resultData, r.resultExtras, r.ordered, r.userId,
                    app.repProcState);
            if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                    "Process cur broadcast " + r + " DELIVERED for app " + app);
            started = true;
        } finally {
            if (!started) {
                if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                        "Process cur broadcast " + r + ": NOT STARTED!");
                r.receiver = null;
                r.curApp = null;
                app.curReceiver = null;
            }
        }
    }

    // 未启动进程的广播接收者需要先启动进程，最后到达这个函数
    public boolean sendPendingBroadcastsLocked(ProcessRecord app) {
        boolean didSomething = false;
        // 前面分析mPendingBroadcast用于存储当前正在等待进程启动的BroadcastRecord
        final BroadcastRecord br = mPendingBroadcast;
        if (br != null && br.curApp.pid == app.pid) {
            if (br.curApp != app) {
                Slog.e(TAG, "App mismatch when sending pending broadcast to "
                        + app.processName + ", intended target is " + br.curApp.processName);
                return false;
            }
            try {
                // 启动完成设置为null
                mPendingBroadcast = null;
                processCurBroadcastLocked(br, app);
                didSomething = true;
            } catch (Exception e) {
                Slog.w(TAG, "Exception in new application when starting receiver "
                        + br.curComponent.flattenToShortString(), e);
                logBroadcastReceiverDiscardLocked(br);
                finishReceiverLocked(br, br.resultCode, br.resultData,
                        br.resultExtras, br.resultAbort, false);
                scheduleBroadcastsLocked();
                // We need to reset the state if we failed to start the receiver.
                br.state = BroadcastRecord.IDLE;
                throw new RuntimeException(e.getMessage());
            }
        }
        return didSomething;
    }

    public void skipPendingBroadcastLocked(int pid) {
        final BroadcastRecord br = mPendingBroadcast;
        if (br != null && br.curApp.pid == pid) {
            br.state = BroadcastRecord.IDLE;
            br.nextReceiver = mPendingBroadcastRecvIndex;
            mPendingBroadcast = null;
            scheduleBroadcastsLocked();
        }
    }

    public void skipCurrentReceiverLocked(ProcessRecord app) {
        BroadcastRecord r = null;
        if (mOrderedBroadcasts.size() > 0) {
            BroadcastRecord br = mOrderedBroadcasts.get(0);
            if (br.curApp == app) {
                r = br;
            }
        }
        if (r == null && mPendingBroadcast != null && mPendingBroadcast.curApp == app) {
            if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                    "[" + mQueueName + "] skip & discard pending app " + r);
            r = mPendingBroadcast;
        }

        if (r != null) {
            skipReceiverLocked(r);
        }
    }

    private void skipReceiverLocked(BroadcastRecord r) {
        logBroadcastReceiverDiscardLocked(r);
        finishReceiverLocked(r, r.resultCode, r.resultData,
                r.resultExtras, r.resultAbort, false);
        scheduleBroadcastsLocked();
    }

    // 驱动广播，所有广播都应该从这里走，然后会到processNextBroadcast
    public void scheduleBroadcastsLocked() {
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Schedule broadcasts ["
                + mQueueName + "]: current="
                + mBroadcastsScheduled);

        // mBroadcastsScheduled用来描述AMS是否已经向它所运行在的线程的消息队列发送了一个类型为
        // BROADCAST_INTENT_MSG的消息。AMS就是通过这个BROADCAST_INTENT_MSG消息类调度保存在无
        // 序广播调度队列mParallelBroadcasts和有序广播调度队列mOrderedBroadcasts中的广播转发任务的
        if (mBroadcastsScheduled) {// 如果true说明消息队列已经存在一个类型为BROADCAST_INTENT_MSG的消息了
            return;
        }
        // 虽然这里只发送了发送广播的消息，但是这一步执行完之后就已经标记广播发送了，因此可以看出广播发送和接
        // 受是异步的，即广播发送者将一个广播发送给AMS后，不会等待AMS将这个广播转发给广播接收者处理
        mHandler.sendMessage(mHandler.obtainMessage(BROADCAST_INTENT_MSG, this));
        mBroadcastsScheduled = true;
    }

    public BroadcastRecord getMatchingOrderedReceiver(IBinder receiver) {
        if (mOrderedBroadcasts.size() > 0) {
            final BroadcastRecord r = mOrderedBroadcasts.get(0);
            if (r != null && r.receiver == receiver) {
                return r;
            }
        }
        return null;
    }

    public boolean finishReceiverLocked(BroadcastRecord r, int resultCode,
                                        String resultData, Bundle resultExtras, boolean resultAbort, boolean waitForServices) {
        final int state = r.state;
        final ActivityInfo receiver = r.curReceiver;
        r.state = BroadcastRecord.IDLE;
        if (state == BroadcastRecord.IDLE) {
            Slog.w(TAG, "finishReceiver [" + mQueueName + "] called but state is IDLE");
        }
        r.receiver = null;
        r.intent.setComponent(null);
        if (r.curApp != null && r.curApp.curReceiver == r) {
            r.curApp.curReceiver = null;
        }
        if (r.curFilter != null) {
            r.curFilter.receiverList.curBroadcast = null;
        }
        r.curFilter = null;
        r.curReceiver = null;
        r.curApp = null;
        mPendingBroadcast = null;

        r.resultCode = resultCode;
        r.resultData = resultData;
        r.resultExtras = resultExtras;
        if (resultAbort && (r.intent.getFlags() & Intent.FLAG_RECEIVER_NO_ABORT) == 0) {
            r.resultAbort = resultAbort;
        } else {
            r.resultAbort = false;
        }

        if (waitForServices && r.curComponent != null && r.queue.mDelayBehindServices
                && r.queue.mOrderedBroadcasts.size() > 0
                && r.queue.mOrderedBroadcasts.get(0) == r) {
            ActivityInfo nextReceiver;
            if (r.nextReceiver < r.receivers.size()) {
                Object obj = r.receivers.get(r.nextReceiver);
                nextReceiver = (obj instanceof ActivityInfo) ? (ActivityInfo) obj : null;
            } else {
                nextReceiver = null;
            }
            // Don't do this if the next receive is in the same process as the current one.
            if (receiver == null || nextReceiver == null
                    || receiver.applicationInfo.uid != nextReceiver.applicationInfo.uid
                    || !receiver.processName.equals(nextReceiver.processName)) {
                // In this case, we are ready to process the next receiver for the current broadcast,
                // but are on a queue that would like to wait for services to finish before moving
                // on.  If there are background services currently starting, then we will go into a
                // special state where we hold off on continuing this broadcast until they are done.
                if (mService.mServices.hasBackgroundServices(r.userId)) {
                    Slog.i(TAG, "Delay finish: " + r.curComponent.flattenToShortString());
                    r.state = BroadcastRecord.WAITING_SERVICES;
                    return false;
                }
            }
        }

        r.curComponent = null;

        // We will process the next receiver right now if this is finishing
        // an app receiver (which is always asynchronous) or after we have
        // come back from calling a receiver.
        return state == BroadcastRecord.APP_RECEIVE
                || state == BroadcastRecord.CALL_DONE_RECEIVE;
    }

    public void backgroundServicesFinishedLocked(int userId) {
        if (mOrderedBroadcasts.size() > 0) {
            BroadcastRecord br = mOrderedBroadcasts.get(0);
            if (br.userId == userId && br.state == BroadcastRecord.WAITING_SERVICES) {
                Slog.i(TAG, "Resuming delayed broadcast");
                br.curComponent = null;
                br.state = BroadcastRecord.IDLE;
                processNextBroadcast(false);
            }
        }
    }

    /**
     * 由前面可知，当一个Activity或者一个Service将一个广播接收者注册到AMS中时，它所在的应用程序进程会先将
     * 这个广播接收者封装成一个类型为InnerReceiver的Binder本地对象，然后再注册到AMS中。因此当AMS要将一个
     * 广播发给一个目标广播接收者处理时，实际上是将这个广播转发给封装了这个目标广播接收者的一个InnerReceiver
     * 对象来处理
     * <p>
     * AMS向一个应用程序进程发送一个广播时，采用的是异步进程间通信方式。前面提到，发送给一个Binder实体对象
     * 的所有异步事物都是保存在一个异步事物队列中的。由于保存在一个异步事物队列中的异步事物在同一时刻只有一
     * 个会得到处理，即只有队列头部的异步事物才会得到处理，因此AMS就可以保证它发送给同一个应用程序的所有广
     * 播都是按照这个发送顺序来串行的被接受和处理
     *
     * @param app         指向一个ProcessRecord对象，用来描述目标广播接收者所运行在的应用程序进程
     * @param receiver    指向一个实现了IIntentReceiver接口的Binder代理对象，用来描述广播接收者
     * @param intent      用来描述即将要发送给目标广播接收者的一个广播
     * @param resultCode
     * @param data
     * @param extras
     * @param ordered
     * @param sticky
     * @param sendingUser
     *
     * @throws RemoteException
     */
    void performReceiveLocked(ProcessRecord app, IIntentReceiver receiver,
                              Intent intent, int resultCode, String data, Bundle extras,
                              boolean ordered, boolean sticky, int sendingUser) throws RemoteException {
        // Send the intent to the receiver asynchronously using one-way binder calls.
        // app不为空，表示进程已经启动，调用ActivityThread.scheduleRegisteredReceiver发送当前广播
        if (app != null) {
            if (app.thread != null) {// 因为是动态注册广播，所以一般不为空
                // If we have an app thread, do the call through that so it is
                // correctly ordered with other one-way calls.
                try {
                    // 这里scheduleRegisteredReceiver函数是一个Binder调用，注释上面说的很清楚，
                    // 要用one-way calls像动态的注册进程发起Binder调用，意思就是在Binder调用里面会
                    // 加上IBinder.FLAG_ONEWAY标记，Binder客户端（动态注册进程）只要一收到Binder调用的
                    // 命令和数据，立马返回到Binder服务端（AMS进程），是一个异步的调用方式。
                    // 大致流程是：
                    // 1) ApplicationThreadNative.ApplicationThreadProxy. scheduleRegisteredReceiver(system_server)
                    // 2) Binder驱动 (Binder驱动进程，ONEWAY)
                    // 3)ApplicationThreadNative. scheduleRegisteredReceiver(应用进程，Binder线程向主线程发送消息)
                    // 4)Binder驱动返回 (Binder驱动进程)
                    // 5)ActivityThread. scheduleRegisteredReceiver(应用进程，处理消息)
                    // 调用ApplicationThread对象的Binder代理对象的函数来向它发送广播
                    app.thread.scheduleRegisteredReceiver(receiver, intent, resultCode,
                            data, extras, ordered, sticky, sendingUser, app.repProcState);
                    // TODO: Uncomment this when (b/28322359) is fixed and we aren't getting
                    // DeadObjectException when the process isn't actually dead.
                    //} catch (DeadObjectException ex) {
                    // Failed to call into the process.  It's dying so just let it die and move on.
                    //    throw ex;
                } catch (RemoteException ex) {
                    // Failed to call into the process. It's either dying or wedged. Kill it gently.
                    synchronized (mService) {
                        Slog.w(TAG, "Can't deliver broadcast to " + app.processName
                                + " (pid " + app.pid + "). Crashing it.");
                        app.scheduleCrash("can't deliver broadcast");
                    }
                    throw ex;
                }
            } else {
                // Application has died. Receiver doesn't exist.
                throw new RemoteException("app.thread must not be null");
            }
        } else {
            // 直接调用与它关联的一个InnerReceiver对象的Binder代理对象的成员函数performReceive来向它发送广播
            receiver.performReceive(intent, resultCode, data, extras, ordered,
                    sticky, sendingUser);
        }
    }

    // AMS将一个广播发给一个目标广播接收者之前，有可能需要检查这个广播的发送者和接收者的权限。这个权限检查是
    // 双向的，即需要检查一个广播发送者是否有权限向一个目标广播接收者发送广播，以及一个目标广播接收者是否有权限
    // 接收一个广播发送者发过来的一个广播。这两个权限主要是调用AMS的成员函数checkComponentPermission来检查
    // 对方的PID以及UID是否符合要求
    private void deliverToRegisteredReceiverLocked(BroadcastRecord r,
                                                   BroadcastFilter filter, boolean ordered, int index) {
        // 标记是否要跳过该广播接收者
        boolean skip = false;
        if (filter.requiredPermission != null) {// 需要检查广播发送者的权限
            int perm = mService.checkComponentPermission(filter.requiredPermission,
                    r.callingPid, r.callingUid, -1, true);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: broadcasting "
                        + r.intent.toString()
                        + " from " + r.callerPackage + " (pid="
                        + r.callingPid + ", uid=" + r.callingUid + ")"
                        + " requires " + filter.requiredPermission
                        + " due to registered receiver " + filter);
                skip = true;
            } else {
                final int opCode = AppOpsManager.permissionToOpCode(filter.requiredPermission);
                if (opCode != AppOpsManager.OP_NONE
                        && mService.mAppOpsService.noteOperation(opCode, r.callingUid,
                        r.callerPackage) != AppOpsManager.MODE_ALLOWED) {
                    Slog.w(TAG, "Appop Denial: broadcasting "
                            + r.intent.toString()
                            + " from " + r.callerPackage + " (pid="
                            + r.callingPid + ", uid=" + r.callingUid + ")"
                            + " requires appop " + AppOpsManager.permissionToOp(
                            filter.requiredPermission)
                            + " due to registered receiver " + filter);
                    skip = true;
                }
            }
        }
        // r.requiredPermissions != null为true表示需要检查广播接收者的权限
        if (!skip && r.requiredPermissions != null && r.requiredPermissions.length > 0) {
            for (int i = 0; i < r.requiredPermissions.length; i++) {
                String requiredPermission = r.requiredPermissions[i];
                int perm = mService.checkComponentPermission(requiredPermission,
                        filter.receiverList.pid, filter.receiverList.uid, -1, true);
                if (perm != PackageManager.PERMISSION_GRANTED) {
                    Slog.w(TAG, "Permission Denial: receiving "
                            + r.intent.toString()
                            + " to " + filter.receiverList.app
                            + " (pid=" + filter.receiverList.pid
                            + ", uid=" + filter.receiverList.uid + ")"
                            + " requires " + requiredPermission
                            + " due to sender " + r.callerPackage
                            + " (uid " + r.callingUid + ")");
                    skip = true;
                    break;
                }
                int appOp = AppOpsManager.permissionToOpCode(requiredPermission);
                if (appOp != AppOpsManager.OP_NONE && appOp != r.appOp
                        && mService.mAppOpsService.noteOperation(appOp,
                        filter.receiverList.uid, filter.packageName)
                        != AppOpsManager.MODE_ALLOWED) {
                    Slog.w(TAG, "Appop Denial: receiving "
                            + r.intent.toString()
                            + " to " + filter.receiverList.app
                            + " (pid=" + filter.receiverList.pid
                            + ", uid=" + filter.receiverList.uid + ")"
                            + " requires appop " + AppOpsManager.permissionToOp(
                            requiredPermission)
                            + " due to sender " + r.callerPackage
                            + " (uid " + r.callingUid + ")");
                    skip = true;
                    break;
                }
            }
        }
        if (!skip && (r.requiredPermissions == null || r.requiredPermissions.length == 0)) {
            int perm = mService.checkComponentPermission(null,
                    filter.receiverList.pid, filter.receiverList.uid, -1, true);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: security check failed when receiving "
                        + r.intent.toString()
                        + " to " + filter.receiverList.app
                        + " (pid=" + filter.receiverList.pid
                        + ", uid=" + filter.receiverList.uid + ")"
                        + " due to sender " + r.callerPackage
                        + " (uid " + r.callingUid + ")");
                skip = true;
            }
        }
        if (!skip && r.appOp != AppOpsManager.OP_NONE
                && mService.mAppOpsService.noteOperation(r.appOp,
                filter.receiverList.uid, filter.packageName)
                != AppOpsManager.MODE_ALLOWED) {
            Slog.w(TAG, "Appop Denial: receiving "
                    + r.intent.toString()
                    + " to " + filter.receiverList.app
                    + " (pid=" + filter.receiverList.pid
                    + ", uid=" + filter.receiverList.uid + ")"
                    + " requires appop " + AppOpsManager.opToName(r.appOp)
                    + " due to sender " + r.callerPackage
                    + " (uid " + r.callingUid + ")");
            skip = true;
        }
        if (!skip) {
            final int allowed = mService.checkAllowBackgroundLocked(filter.receiverList.uid,
                    filter.packageName, -1, true);
            if (allowed == ActivityManager.APP_START_MODE_DISABLED) {
                Slog.w(TAG, "Background execution not allowed: receiving "
                        + r.intent
                        + " to " + filter.receiverList.app
                        + " (pid=" + filter.receiverList.pid
                        + ", uid=" + filter.receiverList.uid + ")");
                skip = true;
            }
        }

        if (!mService.mIntentFirewall.checkBroadcast(r.intent, r.callingUid,
                r.callingPid, r.resolvedType, filter.receiverList.uid)) {
            skip = true;
        }

        if (!skip && (filter.receiverList.app == null || filter.receiverList.app.crashing)) {
            Slog.w(TAG, "Skipping deliver [" + mQueueName + "] " + r
                    + " to " + filter.receiverList + ": process crashing");
            skip = true;
        }

        // 如果要跳过，则设置该广播结束
        if (skip) {
            r.delivery[index] = BroadcastRecord.DELIVERY_SKIPPED;
            return;
        }

        // If permissions need a review before any of the app components can run, we drop
        // the broadcast and if the calling app is in the foreground and the broadcast is
        // explicit we launch the review UI passing it a pending intent to send the skipped
        // broadcast.
        if (Build.PERMISSIONS_REVIEW_REQUIRED) {
            if (!requestStartTargetPermissionsReviewIfNeededLocked(r, filter.packageName,
                    filter.owningUserId)) {
                r.delivery[index] = BroadcastRecord.DELIVERY_SKIPPED;
                return;
            }
        }

        r.delivery[index] = BroadcastRecord.DELIVERY_DELIVERED;

        // If this is not being sent as an ordered broadcast, then we
        // don't want to touch the fields that keep track of the current
        // state of ordered broadcasts.
        // order广播，所有的接收者需要依次以一种同步的方式发送广播，
        // 可以看到order广播在BroadcastRecord保存了几个状态
        if (ordered) {
            // IBinder类型，代表当前的接收者
            r.receiver = filter.receiverList.receiver.asBinder();
            // 当前正在处理的BroadcastFilter，和上面的receiver是对应好的
            r.curFilter = filter;
            filter.receiverList.curBroadcast = r;
            r.state = BroadcastRecord.CALL_IN_RECEIVE;
            if (filter.receiverList.app != null) {
                // Bump hosting application to no longer be in background
                // scheduling class.  Note that we can't do that if there
                // isn't an app...  but we can only be in that case for
                // things that directly call the IActivityManager API, which
                // are already core system stuff so don't matter for this.
                r.curApp = filter.receiverList.app;
                filter.receiverList.app.curReceiver = r;
                mService.updateOomAdjLocked(r.curApp);
            }
        }
        try {
            if (DEBUG_BROADCAST_LIGHT) Slog.i(TAG_BROADCAST,
                    "Delivering to " + filter + " : " + r);
            if (filter.receiverList.app != null && filter.receiverList.app.inFullBackup) {
                // Skip delivery if full backup in progress
                // 如果正在完整备份过程中则跳过分发广播
                // If it's an ordered broadcast, we need to continue to the next receiver.
                // 如果是一个有序广播，我们需要执行下一个广播接收者
                if (ordered) {
                    skipReceiverLocked(r);
                }
            } else {
                // 如果不需要进行权限检查或者通过权限检查，调用performReceiveLocked发送广播
                performReceiveLocked(filter.receiverList.app, filter.receiverList.receiver,
                        new Intent(r.intent), r.resultCode, r.resultData,
                        r.resultExtras, r.ordered, r.initialSticky, r.userId);
            }
            if (ordered) {
                r.state = BroadcastRecord.CALL_DONE_RECEIVE;
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failure sending broadcast " + r.intent, e);
            if (ordered) {
                r.receiver = null;
                r.curFilter = null;
                filter.receiverList.curBroadcast = null;
                if (filter.receiverList.app != null) {
                    filter.receiverList.app.curReceiver = null;
                }
            }
        }
    }

    private boolean requestStartTargetPermissionsReviewIfNeededLocked(
            BroadcastRecord receiverRecord, String receivingPackageName,
            final int receivingUserId) {
        if (!mService.getPackageManagerInternalLocked().isPermissionsReviewRequired(
                receivingPackageName, receivingUserId)) {
            return true;
        }

        final boolean callerForeground = receiverRecord.callerApp != null
                ? receiverRecord.callerApp.setSchedGroup != ProcessList.SCHED_GROUP_BACKGROUND
                : true;

        // Show a permission review UI only for explicit broadcast from a foreground app
        if (callerForeground && receiverRecord.intent.getComponent() != null) {
            IIntentSender target = mService.getIntentSenderLocked(
                    ActivityManager.INTENT_SENDER_BROADCAST, receiverRecord.callerPackage,
                    receiverRecord.callingUid, receiverRecord.userId, null, null, 0,
                    new Intent[]{receiverRecord.intent},
                    new String[]{receiverRecord.intent.resolveType(mService.mContext
                            .getContentResolver())},
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                            | PendingIntent.FLAG_IMMUTABLE, null);

            final Intent intent = new Intent(Intent.ACTION_REVIEW_PERMISSIONS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, receivingPackageName);
            intent.putExtra(Intent.EXTRA_INTENT, new IntentSender(target));

            if (DEBUG_PERMISSIONS_REVIEW) {
                Slog.i(TAG, "u" + receivingUserId + " Launching permission review for package "
                        + receivingPackageName);
            }

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mService.mContext.startActivityAsUser(intent, new UserHandle(receivingUserId));
                }
            });
        } else {
            Slog.w(TAG, "u" + receivingUserId + " Receiving a broadcast in package"
                    + receivingPackageName + " requires a permissions review");
        }

        return false;
    }

    final void scheduleTempWhitelistLocked(int uid, long duration, BroadcastRecord r) {
        if (duration > Integer.MAX_VALUE) {
            duration = Integer.MAX_VALUE;
        }
        // XXX ideally we should pause the broadcast until everything behind this is done,
        // or else we will likely start dispatching the broadcast before we have opened
        // access to the app (there is a lot of asynchronicity behind this).  It is probably
        // not that big a deal, however, because the main purpose here is to allow apps
        // to hold wake locks, and they will be able to acquire their wake lock immediately
        // it just won't be enabled until we get through this work.
        StringBuilder b = new StringBuilder();
        b.append("broadcast:");
        UserHandle.formatUid(b, r.callingUid);
        b.append(":");
        if (r.intent.getAction() != null) {
            b.append(r.intent.getAction());
        } else if (r.intent.getComponent() != null) {
            b.append(r.intent.getComponent().flattenToShortString());
        } else if (r.intent.getData() != null) {
            b.append(r.intent.getData());
        }
        mHandler.obtainMessage(SCHEDULE_TEMP_WHITELIST_MSG, uid, (int) duration, b.toString())
                .sendToTarget();
    }

    // 广播的核心部分，参数fromMsg是用来描述AMS类的成员函数processNextBroadcast是否是用来处理类型为
    // BROADCAST_INTENT_MSG的消息的
    final void processNextBroadcast(boolean fromMsg) {
        synchronized (mService) {
            BroadcastRecord r;

            if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "processNextBroadcast ["
                    + mQueueName + "]: "
                    + mParallelBroadcasts.size() + " broadcasts, "
                    + mOrderedBroadcasts.size() + " ordered broadcasts");

            mService.updateCpuStats();

            if (fromMsg) {// 从msg过来的时候为true
                // 前面说到，如果消息队列里面有BROADCAST_INTENT_MSG消息，该标记为true，
                // 阻止新的消息加入队列，这里开始处理这个消息的时候，将mBroadcastsScheduled变量设置为false，
                // 开始允许新的消息加入。
                mBroadcastsScheduled = false;
            }

            // First, deliver（分发，交付） any non-serialized broadcasts right away（马上）.
            // 无序广播之间不存在相互等待，这里处理的是所有非order的动态广播
            // 处理保存在无序广播调度队列mParallelBroadcasts中的广播发送任务，即把保存在无序广播调度
            // 队列mParallelBroadcasts中的广播发送给它的目标广播接收者处理
            while (mParallelBroadcasts.size() > 0) {
                // 首先保存无序广播调度队列mParallelBroadcasts中的每一个BroadcastRecord对象
                r = mParallelBroadcasts.remove(0);
                r.dispatchTime = SystemClock.uptimeMillis();
                r.dispatchClockTime = System.currentTimeMillis();
                // 调用deliverToRegisteredReceiverLocked向所有的receivers发送广播
                final int N = r.receivers.size();
                if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST, "Processing parallel broadcast ["
                        + mQueueName + "] " + r);
                // 将它所描述的每一个无序广播发送给每一个广播接收者
                for (int i = 0; i < N; i++) {
                    Object target = r.receivers.get(i);
                    if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                            "Delivering non-ordered on [" + mQueueName + "] to registered "
                                    + target + ": " + r);
                    deliverToRegisteredReceiverLocked(r, (BroadcastFilter) target, false, i);
                }
                addBroadcastToHistoryLocked(r);
                if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST, "Done with parallel broadcast ["
                        + mQueueName + "] " + r);
            }

            // Now take care of the next serialized one...

            // If we are waiting for a process to come up to handle the next
            // broadcast, then do nothing at this point.  Just in case, we
            // check that the process we're waiting for still exists.
            // 接下来处理保存在有序广播调度队列mPendingBroadcast中的广播转发任务。有前面可知，有序广播
            // 调度队列mOrderedBroadcast描述的目标广播接收者有可能是静态注册的，而这些静态注册的目标广
            // 播接收者可能还没有启动起来，因此AMS将一个广播发送给它们处理时，首先将它们启动起来。事实上，
            // AMS只需要将他们所运行在的进程启动起来就可以了，因为当这些进程接收到AMS发送的广播后，就会
            // 主动将目标广播接收者启动起来
            // mPendingBroadcast对象是用来描述一个正在等待静态注册的目标广播接收者启动起来的广播转发任务的
            if (mPendingBroadcast != null) {
                if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST,
                        "processNextBroadcast [" + mQueueName + "]: waiting for "
                                + mPendingBroadcast.curApp);

                boolean isDead;
                synchronized (mService.mPidsSelfLocked) {
                    // 检查目标广播接收者所在进程是否已经启动
                    ProcessRecord proc = mService.mPidsSelfLocked.get(mPendingBroadcast.curApp.pid);
                    isDead = proc == null || proc.crashing;
                }
                if (!isDead) {// 如果正在启动，等待
                    // It's still alive, so keep waiting
                    return;
                } else {// 已经启动准备发送广播
                    Slog.w(TAG, "pending app  ["
                            + mQueueName + "]" + mPendingBroadcast.curApp
                            + " died before responding to broadcast");
                    mPendingBroadcast.state = BroadcastRecord.IDLE;
                    mPendingBroadcast.nextReceiver = mPendingBroadcastRecvIndex;
                    mPendingBroadcast = null;
                }
            }

            boolean looped = false;

            // 逐条处理有序广播列表mOrderedBroadcasts中的BroadcastRecord
            do {
                // 判断有序广播调度队列mOrderedBroadcasts是否还有需要处理的广播
                if (mOrderedBroadcasts.size() == 0) {// 没有，说明调度队列中的广播已经处理完成
                    // No more broadcasts pending（等待）, so all done!
                    mService.scheduleAppGcsLocked();
                    if (looped) {
                        // If we had finished the last ordered broadcast, then
                        // make sure all processes have correct oom and schedule（计划）
                        // adjustments（调整）.
                        mService.updateOomAdjLocked();
                    }
                    return;
                }
                // 如果没有处理完成，取出下一个马上要处理的广播BroadcastRecord
                r = mOrderedBroadcasts.get(0);
                boolean forceReceive = false;

                // Ensure that even if something goes awry with the timeout
                // detection, we catch "hung" broadcasts here, discard them,
                // and continue to make progress.
                //
                // This is only done if the system is ready so that PRE_BOOT_COMPLETED
                // receivers don't get executed with timeouts. They're intended for
                // one time heavy lifting after system upgrades and can take
                // significant amounts of time.
                // 获取广播转发任务的目标广播接收者的个数
                int numReceivers = (r.receivers != null) ? r.receivers.size() : 0;
                // 检查前一个目标广播接收者是否在规定的时间内处理完成AMS给它发送的一个有序广播。AMS处理
                // BroadcastRecord对象r所描述的一个广播转发任务时，会将当前时间记录在这个BroadcastRecord
                // 对象中，如果这个广播任务不能在（2*BROADCAST_TIMEOUT*numReceivers）毫秒内完成，即它的目
                // 标广播接收者不能在（2*BROADCAST_TIMEOUT*numReceivers）毫秒内完成AMS给它们发送的一个有
                // 序广播，那么会调用broadcastTimeoutLocked函数来强制结束这个广播转发任务，
                if (mService.mProcessesReady && r.dispatchTime > 0) {
                    long now = SystemClock.uptimeMillis();
                    if ((numReceivers > 0) &&
                            (now > r.dispatchTime + (2 * mTimeoutPeriod * numReceivers))) {
                        Slog.w(TAG, "Hung broadcast ["
                                + mQueueName + "] discarded after timeout failure:"
                                + " now=" + now
                                + " dispatchTime=" + r.dispatchTime
                                + " startTime=" + r.receiverTime
                                + " intent=" + r.intent
                                + " numReceivers=" + numReceivers
                                + " nextReceiver=" + r.nextReceiver
                                + " state=" + r.state);
                        // 出现超时，强制结束
                        broadcastTimeoutLocked(false); // forcibly finish this broadcast
                        // 重置参数，继续处理有序广播调度队列mOrderedBroadcasts的下一个广播转发任务
                        forceReceive = true;
                        r.state = BroadcastRecord.IDLE;
                    }
                }

                // 检测广播转发任务是否正在处理中，即AMS正在将一个有序广播转发给它的前一个目标广播接收处理者，
                // 如果是，AMS就会等待这个目标广播接收者处理完该有序广播，然后再转发给下一个广播接收者处理
                if (r.state != BroadcastRecord.IDLE) {
                    if (DEBUG_BROADCAST) Slog.d(TAG_BROADCAST,
                            "processNextBroadcast("
                                    + mQueueName + ") called when not idle (state="
                                    + r.state + ")");
                    return;
                }

                // 表示广播已经向所有的receiver发送结束或者中途被取消, 如果r.resultAbort为true，会停止处理
                // 当前正在发送的BroadcastRecord，这样优先级比较低的接收者也就收不到这个广播了
                // 检查BroadcastRecord对象r所描述的广播转发任务是否已经处理完成，或者是否已经被强制结束了。
                // 如果是，那么调用函数cancelBroadcastTimeoutLocked来删除前面发送到AMS所运行在的线程的消息
                // 队列中的一个BROADCAST_TIMEOUT_MSG消息,表示BroadcastRecord对象r所描述的广播转发任务已经
                // 在规定时间内处理完成了。接下来就将该广播BroadcastRecord对象从队列中删除，然后赋值为null
                if (r.receivers == null || r.nextReceiver >= numReceivers
                        || r.resultAbort || forceReceive) {
                    // No more receivers for this broadcast!  Send the final
                    // result if requested...
                    if (r.resultTo != null) {
                        try {
                            if (DEBUG_BROADCAST) Slog.i(TAG_BROADCAST,
                                    "Finishing broadcast [" + mQueueName + "] "
                                            + r.intent.getAction() + " app=" + r.callerApp);
                            performReceiveLocked(r.callerApp, r.resultTo,
                                    new Intent(r.intent), r.resultCode,
                                    r.resultData, r.resultExtras, false, false, r.userId);
                            // Set this to null so that the reference
                            // (local and remote) isn't kept in the mBroadcastHistory.
                            r.resultTo = null;
                        } catch (RemoteException e) {
                            r.resultTo = null;
                            Slog.w(TAG, "Failure ["
                                    + mQueueName + "] sending broadcast result of "
                                    + r.intent, e);

                        }
                    }

                    if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Cancelling BROADCAST_TIMEOUT_MSG");
                    cancelBroadcastTimeoutLocked();

                    if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST,
                            "Finished with ordered broadcast " + r);

                    // ... and on to the next...
                    addBroadcastToHistoryLocked(r);
                    if (r.intent.getComponent() == null && r.intent.getPackage() == null
                            && (r.intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                        // This was an implicit broadcast... let's record it for posterity.
                        mService.addBroadcastStatLocked(r.intent.getAction(), r.callerPackage,
                                r.manifestCount, r.manifestSkipCount, r.finishTime - r.dispatchTime);
                    }
                    // BroadcastRecord处理完移除
                    mOrderedBroadcasts.remove(0);
                    r = null;
                    looped = true;
                    continue;
                }
            } while (r == null);// 如果第一次取出的r不为空，则退出循环

            // Get the next receiver...（获取下一个将要处理的广播接收者在其列表中的位置）
            int recIdx = r.nextReceiver++;

            // Keep track of when this receiver started, and make sure there
            // is a timeout message pending to kill it if need be.
            // 保存当前时间，
            r.receiverTime = SystemClock.uptimeMillis();
            if (recIdx == 0) {// 表示第一个开始处理的接收者，也就是BroadcastRecord对象r所描述的广播任务刚被处理
                // 接收者开始处理的时间戳，也就是这个接收者开始处理了，要记录开始时间来计算是否超过超时时间
                // 也就是说这是BroadcastRecord中第一个接收者开始被处理的时间戳，也就是上面BroadcastRecord
                // 超时的起点，可以看到上面超时比较的时候用的就是r.dispatchTime
                r.dispatchTime = r.receiverTime;
                r.dispatchClockTime = System.currentTimeMillis();
                if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST, "Processing ordered broadcast ["
                        + mQueueName + "] " + r);
            }
            // 检查AMS是否已经向它所在的线程的消息队列发送了类型为BROADCAST_TIMEOUT_MSG的消息，如果没有发送，
            // 那么会调用setBroadcastTimeoutLocked函数向这个线程发送一个类型为setBroadcastTimeoutLocked
            // 的消息，并且制定在timeoutTime毫秒后处理。上面指定了r.receiverTime为当前时间表示AMS将一个有序
            // 广播发送给BroadcastRecord对象r所描述的广播转发任务的下一个目标广播接收者处理的时间。如果这个
            // 广播接收者不能再timeoutTime之内完成这个有序广播，AMS就会任务它超时。
            if (!mPendingBroadcastTimeoutMessage) {
                long timeoutTime = r.receiverTime + mTimeoutPeriod;
                if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                        "Submitting BROADCAST_TIMEOUT_MSG ["
                                + mQueueName + "] for " + r + " at " + timeoutTime);
                // 设置超时，传入参数是r.receiverTime + mTimeoutPeriod，也就是开始时间加上超时时间
                // mTimeoutPeriod，mTimeoutPeriod初始化是在BroadcastQueue初始化的时候传入的，
                // 也就是在AMS（AMS构造函数中）中初始化mFgBroadcastQueue和mBgBroadcastQueue时传入的
                // BROADCAST_FG_TIMEOUT = 10 * 1000和BROADCAST_BG_TIMEOUT = 60 * 1000，
                // 这里开始埋了ANR的雷
                setBroadcastTimeoutLocked(timeoutTime);
            }

            // 上面分析的时候对于BroadcastRecord.receivers里面包含两种receiver接收者：order广播下的
            // 动态注册接收者和静态接收者，这两种receiver处理的方式是不一样的，对于order广播下的动态注册
            // receiver而言，接收者进程一定是已经启动的，但是对于静态接收者receiver而言，当前的receiver进程
            // 可能还没有启动，因此动态和静态的receiver处理的逻辑不一样，需要分开处理，而静态接收者又分为进程
            // 已经启动和尚未启动两种情况。

            final BroadcastOptions brOptions = r.options;
            // 得到下一个广播接收者
            final Object nextReceiver = r.receivers.get(recIdx);

            // 如果当前nextReceiver是一个BroadcastFilter类型，说名是一个动态注册接收者，不需要启动一个进程,
            // 直接调用deliverToRegisteredReceiverLocked函数发送广播
            if (nextReceiver instanceof BroadcastFilter) {
                // Simple case: this is a registered receiver who gets
                // a direct call.
                BroadcastFilter filter = (BroadcastFilter) nextReceiver;
                if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                        "Delivering ordered ["
                                + mQueueName + "] to registered "
                                + filter + ": " + r);
                // 上面已经分析
                deliverToRegisteredReceiverLocked(r, filter, r.ordered, recIdx);
                // 检查BroadcastRecord对象r所描述的广播转发任务是否用来转发无序广播的。
                if (r.receiver == null || !r.ordered) {// 如果是
                    // The receiver has already finished, so schedule to
                    // process the next one.
                    if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Quick finishing ["
                            + mQueueName + "]: ordered="
                            + r.ordered + " receiver=" + r.receiver);
                    // 设置IDLE状态，表示AMS不需要等待它的前一个目标广播接收者处理完成一个广播就可以将该广播
                    // 继续发送给它的下一个目标广播接收者处理
                    r.state = BroadcastRecord.IDLE;
                    // 调用下面函数就是为了将一个广播继续发送给BroadcastRecord对象r所描述的广播转发任务的
                    // 下一个目标广播接收者处理的
                    scheduleBroadcastsLocked();
                } else {
                    if (brOptions != null && brOptions.getTemporaryAppWhitelistDuration() > 0) {
                        scheduleTempWhitelistLocked(filter.owningUid,
                                brOptions.getTemporaryAppWhitelistDuration(), r);
                    }
                }
                return;
            }

            // Hard case: need to instantiate the receiver, possibly
            // starting its application process to host it.
            // 如果不是动态的说明是一个静态注册接收者（如果动态的上面if中处理并进行拦截），此时进程可能没有启动
            ResolveInfo info =
                    (ResolveInfo) nextReceiver;
            ComponentName component = new ComponentName(
                    info.activityInfo.applicationInfo.packageName,
                    info.activityInfo.name);

            // 是否跳过该广播接收者不处理
            boolean skip = false;
            if (brOptions != null &&
                    (info.activityInfo.applicationInfo.targetSdkVersion
                            < brOptions.getMinManifestReceiverApiLevel() ||
                            info.activityInfo.applicationInfo.targetSdkVersion
                                    > brOptions.getMaxManifestReceiverApiLevel())) {
                skip = true;// 跳过不处理
            }
            int perm = mService.checkComponentPermission(info.activityInfo.permission,
                    r.callingPid, r.callingUid, info.activityInfo.applicationInfo.uid,
                    info.activityInfo.exported);
            if (!skip && perm != PackageManager.PERMISSION_GRANTED) {
                if (!info.activityInfo.exported) {
                    Slog.w(TAG, "Permission Denial: broadcasting "
                            + r.intent.toString()
                            + " from " + r.callerPackage + " (pid=" + r.callingPid
                            + ", uid=" + r.callingUid + ")"
                            + " is not exported from uid " + info.activityInfo.applicationInfo.uid
                            + " due to receiver " + component.flattenToShortString());
                } else {
                    Slog.w(TAG, "Permission Denial: broadcasting "
                            + r.intent.toString()
                            + " from " + r.callerPackage + " (pid=" + r.callingPid
                            + ", uid=" + r.callingUid + ")"
                            + " requires " + info.activityInfo.permission
                            + " due to receiver " + component.flattenToShortString());
                }
                skip = true;
            } else if (!skip && info.activityInfo.permission != null) {
                final int opCode = AppOpsManager.permissionToOpCode(info.activityInfo.permission);
                if (opCode != AppOpsManager.OP_NONE
                        && mService.mAppOpsService.noteOperation(opCode, r.callingUid,
                        r.callerPackage) != AppOpsManager.MODE_ALLOWED) {
                    Slog.w(TAG, "Appop Denial: broadcasting "
                            + r.intent.toString()
                            + " from " + r.callerPackage + " (pid="
                            + r.callingPid + ", uid=" + r.callingUid + ")"
                            + " requires appop " + AppOpsManager.permissionToOp(
                            info.activityInfo.permission)
                            + " due to registered receiver "
                            + component.flattenToShortString());
                    skip = true;
                }
            }
            if (!skip && info.activityInfo.applicationInfo.uid != Process.SYSTEM_UID &&
                    r.requiredPermissions != null && r.requiredPermissions.length > 0) {
                for (int i = 0; i < r.requiredPermissions.length; i++) {
                    String requiredPermission = r.requiredPermissions[i];
                    try {
                        perm = AppGlobals.getPackageManager().
                                checkPermission(requiredPermission,
                                        info.activityInfo.applicationInfo.packageName,
                                        UserHandle
                                                .getUserId(info.activityInfo.applicationInfo.uid));
                    } catch (RemoteException e) {
                        perm = PackageManager.PERMISSION_DENIED;
                    }
                    if (perm != PackageManager.PERMISSION_GRANTED) {
                        Slog.w(TAG, "Permission Denial: receiving "
                                + r.intent + " to "
                                + component.flattenToShortString()
                                + " requires " + requiredPermission
                                + " due to sender " + r.callerPackage
                                + " (uid " + r.callingUid + ")");
                        skip = true;
                        break;
                    }
                    int appOp = AppOpsManager.permissionToOpCode(requiredPermission);
                    if (appOp != AppOpsManager.OP_NONE && appOp != r.appOp
                            && mService.mAppOpsService.noteOperation(appOp,
                            info.activityInfo.applicationInfo.uid, info.activityInfo.packageName)
                            != AppOpsManager.MODE_ALLOWED) {
                        Slog.w(TAG, "Appop Denial: receiving "
                                + r.intent + " to "
                                + component.flattenToShortString()
                                + " requires appop " + AppOpsManager.permissionToOp(
                                requiredPermission)
                                + " due to sender " + r.callerPackage
                                + " (uid " + r.callingUid + ")");
                        skip = true;
                        break;
                    }
                }
            }
            if (!skip && r.appOp != AppOpsManager.OP_NONE
                    && mService.mAppOpsService.noteOperation(r.appOp,
                    info.activityInfo.applicationInfo.uid, info.activityInfo.packageName)
                    != AppOpsManager.MODE_ALLOWED) {
                Slog.w(TAG, "Appop Denial: receiving "
                        + r.intent + " to "
                        + component.flattenToShortString()
                        + " requires appop " + AppOpsManager.opToName(r.appOp)
                        + " due to sender " + r.callerPackage
                        + " (uid " + r.callingUid + ")");
                skip = true;
            }
            if (!skip) {
                skip = !mService.mIntentFirewall.checkBroadcast(r.intent, r.callingUid,
                        r.callingPid, r.resolvedType, info.activityInfo.applicationInfo.uid);
            }
            boolean isSingleton = false;
            try {
                isSingleton = mService.isSingleton(info.activityInfo.processName,
                        info.activityInfo.applicationInfo,
                        info.activityInfo.name, info.activityInfo.flags);
            } catch (SecurityException e) {
                Slog.w(TAG, e.getMessage());
                skip = true;
            }
            if ((info.activityInfo.flags & ActivityInfo.FLAG_SINGLE_USER) != 0) {
                if (ActivityManager.checkUidPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS,
                        info.activityInfo.applicationInfo.uid)
                        != PackageManager.PERMISSION_GRANTED) {
                    Slog.w(TAG, "Permission Denial: Receiver " + component.flattenToShortString()
                            + " requests FLAG_SINGLE_USER, but app does not hold "
                            + android.Manifest.permission.INTERACT_ACROSS_USERS);
                    skip = true;
                }
            }
            if (!skip) {
                r.manifestCount++;
            } else {
                r.manifestSkipCount++;
            }
            if (r.curApp != null && r.curApp.crashing) {
                // If the target process is crashing, just skip it.
                Slog.w(TAG, "Skipping deliver ordered [" + mQueueName + "] " + r
                        + " to " + r.curApp + ": process crashing");
                skip = true;
            }
            if (!skip) {
                boolean isAvailable = false;
                try {
                    isAvailable = AppGlobals.getPackageManager().isPackageAvailable(
                            info.activityInfo.packageName,
                            UserHandle.getUserId(info.activityInfo.applicationInfo.uid));
                } catch (Exception e) {
                    // all such failures mean we skip this receiver
                    Slog.w(TAG, "Exception getting recipient info for "
                            + info.activityInfo.packageName, e);
                }
                if (!isAvailable) {
                    if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                            "Skipping delivery to " + info.activityInfo.packageName + " / "
                                    + info.activityInfo.applicationInfo.uid
                                    + " : package no longer available");
                    skip = true;
                }
            }

            // If permissions need a review before any of the app components can run, we drop
            // the broadcast and if the calling app is in the foreground and the broadcast is
            // explicit we launch the review UI passing it a pending intent to send the skipped
            // broadcast.
            if (Build.PERMISSIONS_REVIEW_REQUIRED && !skip) {
                if (!requestStartTargetPermissionsReviewIfNeededLocked(r,
                        info.activityInfo.packageName, UserHandle.getUserId(
                                info.activityInfo.applicationInfo.uid))) {
                    skip = true;
                }
            }

            // This is safe to do even if we are skipping the broadcast, and we need
            // this information now to evaluate whether it is going to be allowed to run.
            final int receiverUid = info.activityInfo.applicationInfo.uid;
            // If it's a singleton, it needs to be the same app or a special app
            if (r.callingUid != Process.SYSTEM_UID && isSingleton
                    && mService.isValidSingletonCall(r.callingUid, receiverUid)) {
                info.activityInfo = mService.getActivityInfoForUser(info.activityInfo, 0);
            }
            // 得到ResolveInfo对象info所描述的广播接收者的android:process属性值，即它需要运行在的应用程序
            // 进程的名称，并且保存在变量targetProcess中
            String targetProcess = info.activityInfo.processName;
            // 获取当前广播接收者的进程记录，也就是该静态广播接收者是否已经运行
            ProcessRecord app = mService.getProcessRecordLocked(targetProcess,
                    info.activityInfo.applicationInfo.uid, false);

            if (!skip) {
                final int allowed = mService.checkAllowBackgroundLocked(
                        info.activityInfo.applicationInfo.uid, info.activityInfo.packageName, -1,
                        false);
                if (allowed != ActivityManager.APP_START_MODE_NORMAL) {
                    // We won't allow this receiver to be launched if the app has been
                    // completely disabled from launches, or it was not explicitly sent
                    // to it and the app is in a state that should not receive it
                    // (depending on how checkAllowBackgroundLocked has determined that).
                    if (allowed == ActivityManager.APP_START_MODE_DISABLED) {
                        Slog.w(TAG, "Background execution disabled: receiving "
                                + r.intent + " to "
                                + component.flattenToShortString());
                        skip = true;
                    } else if (((r.intent.getFlags() & Intent.FLAG_RECEIVER_EXCLUDE_BACKGROUND) != 0)
                            || (r.intent.getComponent() == null
                            && r.intent.getPackage() == null
                            && ((r.intent.getFlags()
                            & Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND) == 0))) {
                        Slog.w(TAG, "Background execution not allowed: receiving "
                                + r.intent + " to "
                                + component.flattenToShortString());
                        skip = true;
                    }
                }
            }

            // 跳过，恢复初始状态，开始下一个广播接收者的处理
            if (skip) {
                if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                        "Skipping delivery of ordered [" + mQueueName + "] "
                                + r + " for whatever reason");
                r.delivery[recIdx] = BroadcastRecord.DELIVERY_SKIPPED;
                r.receiver = null;
                r.curFilter = null;
                r.state = BroadcastRecord.IDLE;
                scheduleBroadcastsLocked();
                return;
            }

            r.delivery[recIdx] = BroadcastRecord.DELIVERY_DELIVERED;
            r.state = BroadcastRecord.APP_RECEIVE;
            r.curComponent = component;
            r.curReceiver = info.activityInfo;
            if (DEBUG_MU && r.callingUid > UserHandle.PER_USER_RANGE) {
                Slog.v(TAG_MU, "Updated broadcast record activity info for secondary user, "
                        + info.activityInfo + ", callingUid = " + r.callingUid + ", uid = "
                        + info.activityInfo.applicationInfo.uid);
            }

            if (brOptions != null && brOptions.getTemporaryAppWhitelistDuration() > 0) {
                scheduleTempWhitelistLocked(receiverUid,
                        brOptions.getTemporaryAppWhitelistDuration(), r);
            }

            // Broadcast is being executed, its package can't be stopped.
            try {
                AppGlobals.getPackageManager().setPackageStoppedState(
                        r.curComponent.getPackageName(), false, UserHandle.getUserId(r.callingUid));
            } catch (RemoteException e) {
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Failed trying to unstop package "
                        + r.curComponent.getPackageName() + ": " + e);
            }

            // Is this receiver's application already running?
            // 如果当前进程已经运行，则直接发给该进程，然后返回
            if (app != null && app.thread != null) {
                try {
                    app.addPackage(info.activityInfo.packageName,
                            info.activityInfo.applicationInfo.versionCode, mService.mProcessStats);
                    // 将广播发送给该进程处理
                    processCurBroadcastLocked(r, app);
                    // order广播是一种同步处理方式，因此处理完可以直接return
                    return;
                } catch (RemoteException e) {
                    Slog.w(TAG, "Exception when sending broadcast to "
                            + r.curComponent, e);
                } catch (RuntimeException e) {
                    Slog.wtf(TAG, "Failed sending broadcast to "
                            + r.curComponent + " with " + r.intent, e);
                    // If some unexpected exception happened, just skip
                    // this broadcast.  At this point we are not in the call
                    // from a client, so throwing an exception out from here
                    // will crash the entire system instead of just whoever
                    // sent the broadcast.
                    logBroadcastReceiverDiscardLocked(r);
                    finishReceiverLocked(r, r.resultCode, r.resultData,
                            r.resultExtras, r.resultAbort, false);
                    scheduleBroadcastsLocked();
                    // We need to reset the state if we failed to start the receiver.
                    r.state = BroadcastRecord.IDLE;
                    return;
                }

                // If a dead object exception was thrown -- fall through to
                // restart the application.
            }

            // Not running -- get it started, to be executed when the app comes up.
            if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                    "Need to start app ["
                            + mQueueName + "] " + targetProcess + " for broadcast " + r);

            // 静态接收者进程尚未启动，调用AMS的startProcessLocked函数启动该接收者进程，并将当前正在等待进程
            // 启动的BroadcastRecord存储到mPendingBroadcast里面，这个就是静态广播拉起应用的原理，如果应用
            // 没有启动，注册一个静态广播（一般厂商会修改只能启动自己的应用）。到这里又开始进入等待进程启动的
            // 过程，进程启动完成后才能处理广播
            if ((r.curApp = mService.startProcessLocked(targetProcess,
                    info.activityInfo.applicationInfo, true,
                    r.intent.getFlags() | Intent.FLAG_FROM_BACKGROUND,
                    "broadcast", r.curComponent,
                    (r.intent.getFlags() & Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0, false, false))
                    == null) {// 如果启动失败
                // Ah, this recipient is unavailable.  Finish it if necessary,
                // and mark the broadcast record as ready for the next.
                Slog.w(TAG, "Unable to launch app "
                        + info.activityInfo.applicationInfo.packageName + "/"
                        + info.activityInfo.applicationInfo.uid + " for broadcast "
                        + r.intent + ": process is bad");
                logBroadcastReceiverDiscardLocked(r);
                // 结束广播发送任务
                finishReceiverLocked(r, r.resultCode, r.resultData,
                        r.resultExtras, r.resultAbort, false);
                scheduleBroadcastsLocked();
                r.state = BroadcastRecord.IDLE;
                return;
            }

            // 正在启动接收者进程，将正在启动的BroadcastRecord记录存储到mPendingBroadcast中，同时将当前正在
            // 启动的接收者进程在所有接收者中的索引存储到mPendingBroadcastRecvIndex，如果当前广播接收者处理
            // 完，需要继续从mPendingBroadcastRecvIndex计算到下一个接收者发送当前广播
            mPendingBroadcast = r;
            mPendingBroadcastRecvIndex = recIdx;
        }
    }

    // 设置超时时间
    final void setBroadcastTimeoutLocked(long timeoutTime) {
        if (!mPendingBroadcastTimeoutMessage) {
            Message msg = mHandler.obtainMessage(BROADCAST_TIMEOUT_MSG, this);
            mHandler.sendMessageAtTime(msg, timeoutTime);
            mPendingBroadcastTimeoutMessage = true;
        }
    }

    final void cancelBroadcastTimeoutLocked() {
        if (mPendingBroadcastTimeoutMessage) {
            mHandler.removeMessages(BROADCAST_TIMEOUT_MSG, this);
            mPendingBroadcastTimeoutMessage = false;
        }
    }

    /**
     * AMS在处理order模式广播接收者时，会为每一个order模式广播处理设置超时时间，并且超时时间是各个接收者之间
     * 相互独立，前面分析超时通过setBroadcastTimeoutLocked函数建立超时时间点消息的，本以为每次处理完以后，
     * 调用cancelBroadcastTimeoutLocked函数取消当前接收者的超时消息，但是实际上用了一种更加高效的方法处理了
     * 超时机制，在每个order模式receiver开始处理的时候设置超时消息，BroadcastRecord.receiverTime记录了
     * 当前receiver开始处理的时间点
     * <p>
     * BroadcastRecord超时：注意这里的超时和我们常说的广播超时ANR不是一个概念，这个BroadcastRecord超时是针
     * 对当前BroadcastRecord. receivers里面剩余的所有的成员而言的，比如说当前receivers里面剩余4个广播接收者，
     * 那么这个超时的时间： 2*(4*mTimeoutPeriod) 至于这个mTimeoutPeriod，对于前台广播mFgBroadcastQueue
     * 和mBgBroadcastQueue后台广播时间:BROADCAST_FG_TIMEOUT = 10 * 1000
     * 和BROADCAST_BG_TIMEOUT = 60 * 1000（AMS中），也就是对于有4个成员的receivers 后台广播的
     * BroadcastRecord而言超时的时间为：2*(4*10*1000)=80 000ms=80s，当出现这种超时的时候，当前正在处理的
     * 广播接收者会出现ANR，并且导致后面尚未接收到广播的收不到当前的广播。broadcastTimeoutLocked函数会将
     * mOrderedBroadcasts中下标为0的应用进程ANR，下面forceReceive设置为true，走到下面的if判断里面会将当前
     * 正在处理的BroadcastRecord从mOrderedBroadcasts中remove掉，导致receivers后面的成员没有收到广播，
     * 并且将r设置为null，接着就处理broadcastTimeoutLocked里面的下一个广播记录BroadcastRecord。例如，
     * receivers里面包含4个成员，但是第1个接收者在80s内都没有处理完，那么这个接收者进程会收到ANR，
     * 并且后面的3个广播接收者都收不到当前的广播。
     * <p>
     * 注释来自于：http://blog.csdn.net/houliang120/article/details/51607170
     *
     * @param fromMsg
     */
    final void broadcastTimeoutLocked(boolean fromMsg) {
        if (fromMsg) {
            mPendingBroadcastTimeoutMessage = false;
        }

        if (mOrderedBroadcasts.size() == 0) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        BroadcastRecord r = mOrderedBroadcasts.get(0);
        if (fromMsg) {
            if (mService.mDidDexOpt) {
                // Delay timeouts until dexopt finishes.
                mService.mDidDexOpt = false;
                long timeoutTime = SystemClock.uptimeMillis() + mTimeoutPeriod;
                setBroadcastTimeoutLocked(timeoutTime);
                return;
            }
            if (!mService.mProcessesReady) {
                // Only process broadcast timeouts if the system is ready. That way
                // PRE_BOOT_COMPLETED broadcasts can't timeout as they are intended
                // to do heavy lifting for system up.
                return;
            }

            long timeoutTime = r.receiverTime + mTimeoutPeriod;
            // 举个例子：
            // 假设receiverA在100s的时候开始处理，超时时间为10s，那么receiverA的超时时间点就是110s，
            // 但是receiverA在105s的时候已经处理完了，于是在105s的时候开始receiverB，但是并没有取消
            // receiverA的超时消息，也就是在110s的时候仍然会走到这里的broadcastTimeoutLocked函数，
            // receiverB开始处理，这时候r.receiverTime就是105s，对于receiverB而言超时时间应该是115s，
            // 假设receiverB需要在112s才能处理完，在110s的时候broadcastTimeoutLocked函数处理的时候
            // timeoutTime=115s，now=110s，这时候不会进行实际的超时处理，因为还没有到真实的超时时间，
            // 所以重新设置超时时间点在115s。就这样根据当前BroadcastRecord.receiverTime的时间反复调整。
            if (timeoutTime > now) {// 判断超时时间点和现在时间的关系，此处还没有超时
                // We can observe premature timeouts because we do not cancel and reset the
                // broadcast timeout message after each receiver finishes.  Instead, we set up
                // an initial timeout then kick it down the road a little further as needed
                // when it expires.
                // 因为没有在每个广播处理完之后取消或者重置超时时间，从而导致提前检测到超时消息。取而代之，
                // 设置一个初始超时时间点，然后每次出现超时事件的时候根据需要进行处理或者调整超时机制。
                if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST,
                        "Premature timeout ["
                                + mQueueName + "] @ " + now + ": resetting BROADCAST_TIMEOUT_MSG for "
                                + timeoutTime);
                setBroadcastTimeoutLocked(timeoutTime);
                return;
            }
        }

        BroadcastRecord br = mOrderedBroadcasts.get(0);
        if (br.state == BroadcastRecord.WAITING_SERVICES) {
            // In this case the broadcast had already finished, but we had decided to wait
            // for started services to finish as well before going on.  So if we have actually
            // waited long enough time timeout the broadcast, let's give up on the whole thing
            // and just move on to the next.
            Slog.i(TAG, "Waited long enough for: " + (br.curComponent != null
                    ? br.curComponent.flattenToShortString() : "(null)"));
            br.curComponent = null;
            br.state = BroadcastRecord.IDLE;
            processNextBroadcast(false);
            return;
        }

        Slog.w(TAG, "Timeout of broadcast " + r + " - receiver=" + r.receiver
                + ", started " + (now - r.receiverTime) + "ms ago");
        r.receiverTime = now;
        r.anrCount++;

        // Current receiver has passed its expiration date.
        if (r.nextReceiver <= 0) {
            Slog.w(TAG, "Timeout on receiver with nextReceiver <= 0");
            return;
        }

        ProcessRecord app = null;
        String anrMessage = null;

        Object curReceiver = r.receivers.get(r.nextReceiver - 1);
        r.delivery[r.nextReceiver - 1] = BroadcastRecord.DELIVERY_TIMEOUT;
        Slog.w(TAG, "Receiver during timeout: " + curReceiver);
        logBroadcastReceiverDiscardLocked(r);
        if (curReceiver instanceof BroadcastFilter) {
            BroadcastFilter bf = (BroadcastFilter) curReceiver;
            if (bf.receiverList.pid != 0
                    && bf.receiverList.pid != ActivityManagerService.MY_PID) {
                synchronized (mService.mPidsSelfLocked) {
                    app = mService.mPidsSelfLocked.get(
                            bf.receiverList.pid);
                }
            }
        } else {
            app = r.curApp;
        }

        if (app != null) {
            anrMessage = "Broadcast of " + r.intent.toString();
        }

        if (mPendingBroadcast == r) {
            mPendingBroadcast = null;
        }

        // Move on to the next receiver.
        finishReceiverLocked(r, r.resultCode, r.resultData,
                r.resultExtras, r.resultAbort, false);
        scheduleBroadcastsLocked();

        if (anrMessage != null) {
            // Post the ANR to the handler since we do not want to process ANRs while
            // potentially holding our lock.
            mHandler.post(new AppNotResponding(app, anrMessage));
        }
    }

    private final int ringAdvance(int x, final int increment, final int ringSize) {
        x += increment;
        if (x < 0) return (ringSize - 1);
        else if (x >= ringSize) return 0;
        else return x;
    }

    private final void addBroadcastToHistoryLocked(BroadcastRecord r) {
        if (r.callingUid < 0) {
            // This was from a registerReceiver() call; ignore it.
            return;
        }
        r.finishTime = SystemClock.uptimeMillis();

        mBroadcastHistory[mHistoryNext] = r;
        mHistoryNext = ringAdvance(mHistoryNext, 1, MAX_BROADCAST_HISTORY);

        mBroadcastSummaryHistory[mSummaryHistoryNext] = r.intent;
        mSummaryHistoryEnqueueTime[mSummaryHistoryNext] = r.enqueueClockTime;
        mSummaryHistoryDispatchTime[mSummaryHistoryNext] = r.dispatchClockTime;
        mSummaryHistoryFinishTime[mSummaryHistoryNext] = System.currentTimeMillis();
        mSummaryHistoryNext = ringAdvance(mSummaryHistoryNext, 1, MAX_BROADCAST_SUMMARY_HISTORY);
    }

    boolean cleanupDisabledPackageReceiversLocked(
            String packageName, Set<String> filterByClasses, int userId, boolean doit) {
        boolean didSomething = false;
        for (int i = mParallelBroadcasts.size() - 1; i >= 0; i--) {
            didSomething |= mParallelBroadcasts.get(i).cleanupDisabledPackageReceiversLocked(
                    packageName, filterByClasses, userId, doit);
            if (!doit && didSomething) {
                return true;
            }
        }

        for (int i = mOrderedBroadcasts.size() - 1; i >= 0; i--) {
            didSomething |= mOrderedBroadcasts.get(i).cleanupDisabledPackageReceiversLocked(
                    packageName, filterByClasses, userId, doit);
            if (!doit && didSomething) {
                return true;
            }
        }

        return didSomething;
    }

    final void logBroadcastReceiverDiscardLocked(BroadcastRecord r) {
        final int logIndex = r.nextReceiver - 1;
        if (logIndex >= 0 && logIndex < r.receivers.size()) {
            Object curReceiver = r.receivers.get(logIndex);
            if (curReceiver instanceof BroadcastFilter) {
                BroadcastFilter bf = (BroadcastFilter) curReceiver;
                EventLog.writeEvent(EventLogTags.AM_BROADCAST_DISCARD_FILTER,
                        bf.owningUserId, System.identityHashCode(r),
                        r.intent.getAction(), logIndex, System.identityHashCode(bf));
            } else {
                ResolveInfo ri = (ResolveInfo) curReceiver;
                EventLog.writeEvent(EventLogTags.AM_BROADCAST_DISCARD_APP,
                        UserHandle.getUserId(ri.activityInfo.applicationInfo.uid),
                        System.identityHashCode(r), r.intent.getAction(), logIndex, ri.toString());
            }
        } else {
            if (logIndex < 0) Slog.w(TAG,
                    "Discarding broadcast before first receiver is invoked: " + r);
            EventLog.writeEvent(EventLogTags.AM_BROADCAST_DISCARD_APP,
                    -1, System.identityHashCode(r),
                    r.intent.getAction(),
                    r.nextReceiver,
                    "NONE");
        }
    }

    final boolean dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args,
                             int opti, boolean dumpAll, String dumpPackage, boolean needSep) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        if (mParallelBroadcasts.size() > 0 || mOrderedBroadcasts.size() > 0
                || mPendingBroadcast != null) {
            boolean printed = false;
            for (int i = mParallelBroadcasts.size() - 1; i >= 0; i--) {
                BroadcastRecord br = mParallelBroadcasts.get(i);
                if (dumpPackage != null && !dumpPackage.equals(br.callerPackage)) {
                    continue;
                }
                if (!printed) {
                    if (needSep) {
                        pw.println();
                    }
                    needSep = true;
                    printed = true;
                    pw.println("  Active broadcasts [" + mQueueName + "]:");
                }
                pw.println("  Active Broadcast " + mQueueName + " #" + i + ":");
                br.dump(pw, "    ", sdf);
            }
            printed = false;
            needSep = true;
            for (int i = mOrderedBroadcasts.size() - 1; i >= 0; i--) {
                BroadcastRecord br = mOrderedBroadcasts.get(i);
                if (dumpPackage != null && !dumpPackage.equals(br.callerPackage)) {
                    continue;
                }
                if (!printed) {
                    if (needSep) {
                        pw.println();
                    }
                    needSep = true;
                    printed = true;
                    pw.println("  Active ordered broadcasts [" + mQueueName + "]:");
                }
                pw.println("  Active Ordered Broadcast " + mQueueName + " #" + i + ":");
                mOrderedBroadcasts.get(i).dump(pw, "    ", sdf);
            }
            if (dumpPackage == null || (mPendingBroadcast != null
                    && dumpPackage.equals(mPendingBroadcast.callerPackage))) {
                if (needSep) {
                    pw.println();
                }
                pw.println("  Pending broadcast [" + mQueueName + "]:");
                if (mPendingBroadcast != null) {
                    mPendingBroadcast.dump(pw, "    ", sdf);
                } else {
                    pw.println("    (null)");
                }
                needSep = true;
            }
        }

        int i;
        boolean printed = false;

        i = -1;
        int lastIndex = mHistoryNext;
        int ringIndex = lastIndex;
        do {
            // increasing index = more recent entry, and we want to print the most
            // recent first and work backwards, so we roll through the ring backwards.
            ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_HISTORY);
            BroadcastRecord r = mBroadcastHistory[ringIndex];
            if (r == null) {
                continue;
            }

            i++; // genuine record of some sort even if we're filtering it out
            if (dumpPackage != null && !dumpPackage.equals(r.callerPackage)) {
                continue;
            }
            if (!printed) {
                if (needSep) {
                    pw.println();
                }
                needSep = true;
                pw.println("  Historical broadcasts [" + mQueueName + "]:");
                printed = true;
            }
            if (dumpAll) {
                pw.print("  Historical Broadcast " + mQueueName + " #");
                pw.print(i);
                pw.println(":");
                r.dump(pw, "    ", sdf);
            } else {
                pw.print("  #");
                pw.print(i);
                pw.print(": ");
                pw.println(r);
                pw.print("    ");
                pw.println(r.intent.toShortString(false, true, true, false));
                if (r.targetComp != null && r.targetComp != r.intent.getComponent()) {
                    pw.print("    targetComp: ");
                    pw.println(r.targetComp.toShortString());
                }
                Bundle bundle = r.intent.getExtras();
                if (bundle != null) {
                    pw.print("    extras: ");
                    pw.println(bundle.toString());
                }
            }
        } while (ringIndex != lastIndex);

        if (dumpPackage == null) {
            lastIndex = ringIndex = mSummaryHistoryNext;
            if (dumpAll) {
                printed = false;
                i = -1;
            } else {
                // roll over the 'i' full dumps that have already been issued
                for (int j = i;
                     j > 0 && ringIndex != lastIndex; ) {
                    ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_SUMMARY_HISTORY);
                    BroadcastRecord r = mBroadcastHistory[ringIndex];
                    if (r == null) {
                        continue;
                    }
                    j--;
                }
            }
            // done skipping; dump the remainder of the ring. 'i' is still the ordinal within
            // the overall broadcast history.
            do {
                ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_SUMMARY_HISTORY);
                Intent intent = mBroadcastSummaryHistory[ringIndex];
                if (intent == null) {
                    continue;
                }
                if (!printed) {
                    if (needSep) {
                        pw.println();
                    }
                    needSep = true;
                    pw.println("  Historical broadcasts summary [" + mQueueName + "]:");
                    printed = true;
                }
                if (!dumpAll && i >= 50) {
                    pw.println("  ...");
                    break;
                }
                i++;
                pw.print("  #");
                pw.print(i);
                pw.print(": ");
                pw.println(intent.toShortString(false, true, true, false));
                pw.print("    ");
                TimeUtils.formatDuration(mSummaryHistoryDispatchTime[ringIndex]
                        - mSummaryHistoryEnqueueTime[ringIndex], pw);
                pw.print(" dispatch ");
                TimeUtils.formatDuration(mSummaryHistoryFinishTime[ringIndex]
                        - mSummaryHistoryDispatchTime[ringIndex], pw);
                pw.println(" finish");
                pw.print("    enq=");
                pw.print(sdf.format(new Date(mSummaryHistoryEnqueueTime[ringIndex])));
                pw.print(" disp=");
                pw.print(sdf.format(new Date(mSummaryHistoryDispatchTime[ringIndex])));
                pw.print(" fin=");
                pw.println(sdf.format(new Date(mSummaryHistoryFinishTime[ringIndex])));
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    pw.print("    extras: ");
                    pw.println(bundle.toString());
                }
            } while (ringIndex != lastIndex);
        }

        return needSep;
    }
}
