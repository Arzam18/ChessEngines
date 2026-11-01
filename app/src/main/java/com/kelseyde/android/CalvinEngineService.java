package com.kelseyde.calvin.android;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import ccc.chess.engines.BuildConfig;
import com.kelseyde.calvin.uci.UCI;
import com.kelseyde.calvin.uci.UCICommand;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Hosts Calvin in this app's ART process. This is intentionally not an OEX executable-provider entry:
 * clients bind to this service and exchange ordinary UCI command/output strings over Messenger.
 */
public class CalvinEngineService extends Service {

    private static final long EXPECTED_NETWORK_BYTES = 12_589_058L;

    private HandlerThread engineThread;
    private Messenger serviceMessenger;
    private volatile Messenger activeClient;
    private volatile boolean initialized;

    @Override
    public void onCreate() {
        super.onCreate();
        engineThread = new HandlerThread("CalvinEngineService");
        engineThread.start();
        Handler handler = new Handler(engineThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                handleClientMessage(msg);
            }
        };
        serviceMessenger = new Messenger(handler);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return serviceMessenger.getBinder();
    }

    private void handleClientMessage(Message message) {
        try {
            switch (message.what) {
                case CalvinEngineProtocol.MSG_REGISTER_CLIENT:
                    if (!claimController(message.replyTo)) return;
                    ensureInitialized();
                    break;
                case CalvinEngineProtocol.MSG_UNREGISTER_CLIENT:
                    if (isActiveClient(message.replyTo)) {
                        if (initialized) UCICommand.parse("stop").execute();
                        activeClient = null;
                    }
                    break;
                case CalvinEngineProtocol.MSG_COMMAND:
                    if (!claimController(message.replyTo)) return;
                    ensureInitialized();
                    Bundle data = message.getData();
                    String command = data != null
                            ? data.getString(CalvinEngineProtocol.KEY_COMMAND, "") : "";
                    if (!command.trim().isEmpty()) {
                        UCICommand.parse(command).execute();
                    }
                    break;
                default:
                    superHandleUnknown(message.what);
                    break;
            }
        } catch (Throwable error) {
            publishError("Calvin engine request failed: " + error.getClass().getSimpleName()
                    + ": " + String.valueOf(error.getMessage()));
        }
    }

    private void superHandleUnknown(int what) {
        publishError("Unsupported Calvin service message: " + what);
    }

    private boolean claimController(Messenger requested) {
        if (requested == null) {
            publishError("Calvin client did not provide a reply Messenger");
            return false;
        }
        if (activeClient == null || isSameClient(activeClient, requested)) {
            activeClient = requested;
            return true;
        }
        sendError(requested, "Calvin is busy with another GUI session");
        return false;
    }

    private boolean isActiveClient(Messenger requested) {
        return activeClient != null && requested != null && isSameClient(activeClient, requested);
    }

    private boolean isSameClient(Messenger first, Messenger second) {
        return first.getBinder().equals(second.getBinder());
    }

    private synchronized void ensureInitialized() throws IOException {
        if (initialized) return;

        // This must happen before UCI/Engine/NNUE is first initialized: NNUE loads its network statically.
        File networkFile = new File(getFilesDir(), "calvin1024_8b-" + BuildConfig.VERSION_CODE + ".nnue");
        copyBundledNetwork(networkFile);
        System.setProperty("calvin.nnue.path", networkFile.getAbsolutePath());

        // UCI.setOutputListener initializes Calvin's engine singleton. The network path is already configured.
        UCI.setOutputListener(this::publishOutput);
        UCI.Options.pretty = false;
        UCI.Options.output = true;
        initialized = true;
    }

    private void copyBundledNetwork(File destination) throws IOException {
        if (destination.isFile() && destination.length() == EXPECTED_NETWORK_BYTES) {
            return;
        }
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (InputStream input = getAssets().open("calvin1024_8b.nnue");
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        if (temporary.length() != EXPECTED_NETWORK_BYTES) {
            long actualBytes = temporary.length();
            temporary.delete();
            throw new IOException("Bundled Calvin NNUE has an unexpected size: " + actualBytes
                    + " bytes; expected " + EXPECTED_NETWORK_BYTES);
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Cannot replace the previous Calvin NNUE file");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Cannot install the Calvin NNUE file");
        }
    }

    private void publishOutput(String line) {
        Messenger client = activeClient;
        if (client == null) return;
        Message output = Message.obtain(null, CalvinEngineProtocol.MSG_OUTPUT);
        Bundle data = new Bundle();
        data.putString(CalvinEngineProtocol.KEY_LINE, line);
        output.setData(data);
        try {
            client.send(output);
        } catch (RemoteException deadClient) {
            activeClient = null;
        }
    }

    private void publishError(String line) {
        Messenger client = activeClient;
        if (client != null) sendError(client, line);
    }

    private void sendError(Messenger client, String line) {
        Message output = Message.obtain(null, CalvinEngineProtocol.MSG_ERROR);
        Bundle data = new Bundle();
        data.putString(CalvinEngineProtocol.KEY_LINE, line);
        output.setData(data);
        try {
            client.send(output);
        } catch (RemoteException ignored) {
            if (isActiveClient(client)) activeClient = null;
        }
    }

    @Override
    public void onDestroy() {
        if (initialized) {
            // Keep a non-null sink installed so UCI's hosted mode cannot call System.exit(0).
            UCI.setOutputListener(line -> { });
            UCICommand.parse("quit").execute();
            UCI.setOutputListener(null);
        }
        if (engineThread != null) {
            engineThread.quitSafely();
        }
        activeClient = null;
        super.onDestroy();
    }
}
