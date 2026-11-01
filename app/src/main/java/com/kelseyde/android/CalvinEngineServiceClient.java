package com.kelseyde.calvin.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.util.ArrayDeque;
import java.util.Queue;

/** Small client helper for OEX itself or another Android application implementing this service contract. */
public final class CalvinEngineServiceClient {

    public interface Listener {
        void onEngineOutput(String line);
        void onEngineError(String line);
        void onDisconnected();
        default void onConnected() { }
    }

    private final Context context;
    private final Listener listener;
    private final Messenger incoming;
    private volatile Messenger service;
    private boolean bound;
    private final Queue<String> pendingCommands = new ArrayDeque<>();

    public CalvinEngineServiceClient(Context context, Handler callbackHandler, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.incoming = new Messenger(new Handler(callbackHandler.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                Bundle data = msg.getData();
                String line = data != null
                        ? data.getString(CalvinEngineProtocol.KEY_LINE, "") : "";
                if (msg.what == CalvinEngineProtocol.MSG_OUTPUT) {
                    CalvinEngineServiceClient.this.listener.onEngineOutput(line);
                } else if (msg.what == CalvinEngineProtocol.MSG_ERROR) {
                    CalvinEngineServiceClient.this.listener.onEngineError(line);
                }
            }
        });
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = new Messenger(binder);
            send(CalvinEngineProtocol.MSG_REGISTER_CLIENT, null);
            synchronized (pendingCommands) {
                while (!pendingCommands.isEmpty()) {
                    sendCommandNow(pendingCommands.remove());
                }
            }
            listener.onConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            listener.onDisconnected();
        }
    };

    public boolean bind() {
        if (bound) return true;
        Intent intent = new Intent(CalvinEngineProtocol.ACTION_BIND);
        intent.setPackage(CalvinEngineProtocol.PACKAGE_NAME);
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        return bound;
    }

    public void sendCommand(String command) {
        synchronized (pendingCommands) {
            if (service == null) {
                pendingCommands.add(command);
                return;
            }
            sendCommandNow(command);
        }
    }

    private void sendCommandNow(String command) {
        Bundle data = new Bundle();
        data.putString(CalvinEngineProtocol.KEY_COMMAND, command);
        send(CalvinEngineProtocol.MSG_COMMAND, data);
    }

    public void unbind() {
        if (!bound) return;
        send(CalvinEngineProtocol.MSG_UNREGISTER_CLIENT, null);
        context.unbindService(connection);
        bound = false;
        service = null;
        synchronized (pendingCommands) {
            pendingCommands.clear();
        }
    }

    private void send(int what, Bundle data) {
        if (service == null) return;
        Message message = Message.obtain(null, what);
        message.replyTo = incoming;
        if (data != null) message.setData(data);
        try {
            service.send(message);
        } catch (RemoteException error) {
            listener.onEngineError("Unable to send command to Calvin: " + error.getMessage());
        }
    }
}
