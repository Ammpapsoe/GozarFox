package com.gozar;

import static org.torproject.jni.TorService.STATUS_OFF;
import static org.torproject.jni.TorService.STATUS_ON;
import static org.torproject.jni.TorService.STATUS_STARTING;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.IBinder;

import net.freehaven.tor.control.TorControlCommands;
import net.freehaven.tor.control.TorControlConnection;

import org.torproject.jni.TorService;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class OrbotTorController {
    interface Listener {
        void onStatus(String status);
    }

    private static final ExecutorService mExecutor = Executors.newCachedThreadPool();

    private final Context context;
    private final Listener listener;
    final AtomicBoolean torStartRequested = new AtomicBoolean(false);
    volatile boolean shouldUnbindTorService;
    volatile String mCurrentStatus = STATUS_OFF;
    volatile TorControlConnection conn;
    volatile int socksPort = -1;
    volatile boolean remoteDisconnected;
    ServiceConnection torServiceConnection;
    private LocalSocket controlSocket;

    OrbotTorController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    synchronized void startTorService() throws Exception {
        if (!torStartRequested.compareAndSet(false, true)) return;
        PrintWriter defaults = new PrintWriter(new FileWriter(TorService.getDefaultsTorrc(context), false));
        defaults.println("DNSPort 0");
        defaults.println("TransPort 0");
        defaults.println("DisableNetwork 1");
        defaults.flush();
        defaults.close();
        remoteDisconnected = false;
        mCurrentStatus = STATUS_STARTING;
        listener.onStatus(STATUS_STARTING);
        torServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                try {
                    connectControlSocket();
                    if (!torStartRequested.get() || conn == null) return;
                    Thread.sleep(1000);
                    if (!torStartRequested.get()) return;
                    initControlConnection();
                    if (conn == null) return;
                    conn.setEvents(Collections.singletonList(TorControlCommands.EVENT_STATUS_CLIENT));
                } catch (Exception error) {
                    remoteDisconnected = true;
                    mCurrentStatus = STATUS_OFF;
                    torStartRequested.set(false);
                    clearControl();
                    listener.onStatus(STATUS_OFF);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                remoteDisconnected = true;
                mCurrentStatus = STATUS_OFF;
                torStartRequested.set(false);
                clearControl();
                listener.onStatus(STATUS_OFF);
            }

            @Override
            public void onBindingDied(ComponentName componentName) {
                remoteDisconnected = true;
                if (torServiceConnection == this) {
                    if (shouldUnbindTorService) {
                        context.unbindService(this);
                        shouldUnbindTorService = false;
                    }
                    clearControl();
                    torServiceConnection = null;
                    torStartRequested.set(false);
                }
                mCurrentStatus = STATUS_OFF;
                listener.onStatus(STATUS_OFF);
            }
        };
        Intent serviceIntent = new Intent(context, TorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            shouldUnbindTorService = context.bindService(serviceIntent, Context.BIND_AUTO_CREATE, mExecutor, torServiceConnection);
        } else {
            shouldUnbindTorService = context.bindService(serviceIntent, torServiceConnection, Context.BIND_AUTO_CREATE);
        }
        if (!shouldUnbindTorService) {
            torServiceConnection = null;
            clearControl();
            torStartRequested.set(false);
            mCurrentStatus = STATUS_OFF;
            throw new IOException("bindService failed");
        }
    }

    private void connectControlSocket() throws IOException, InterruptedException {
        File socketFile = new File(new File(context.getDir("TorService", Context.MODE_PRIVATE), "data"), "ControlSocket");
        while (conn == null) {
            if (!torStartRequested.get()) return;
            LocalSocket candidate = new LocalSocket();
            try {
                candidate.connect(new LocalSocketAddress(socketFile.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
                TorControlConnection control = new TorControlConnection(candidate.getInputStream(), candidate.getOutputStream());
                controlSocket = candidate;
                control.launchThread(true);
                control.authenticate(new byte[0]);
                conn = control;
            } catch (IOException error) {
                try {
                    candidate.close();
                } catch (IOException ignored) {
                }
                Thread.sleep(500);
            }
        }
    }

    private void initControlConnection() throws IOException {
        if (conn == null) return;
        String confSocks = conn.getInfo("net/listeners/socks");
        StringTokenizer st = new StringTokenizer(confSocks, " ");
        while (st.hasMoreTokens()) {
            String value = st.nextToken();
            int split = value.lastIndexOf(':');
            if (split > 0) {
                String port = value.substring(split + 1).replace("\"", "");
                socksPort = Integer.parseInt(port);
            }
        }
        mCurrentStatus = STATUS_ON;
        listener.onStatus(STATUS_ON);
    }

    private void clearControl() {
        conn = null;
        if (controlSocket != null) {
            try {
                controlSocket.close();
            } catch (IOException ignored) {
            }
            controlSocket = null;
        }
    }

    void stopTor() {
        TorControlConnection control = conn;
        if (control != null) {
            try {
                control.shutdownTor(TorControlCommands.SIGNAL_SHUTDOWN);
            } catch (Exception ignored) {
            }
        }
        if (shouldUnbindTorService) {
            try {
                context.unbindService(torServiceConnection);
            } catch (Exception ignored) {
            }
            shouldUnbindTorService = false;
        }
        clearControl();
        torServiceConnection = null;
        torStartRequested.set(false);
        socksPort = -1;
        mCurrentStatus = STATUS_OFF;
        listener.onStatus(STATUS_OFF);
    }
}
