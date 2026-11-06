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
import android.util.Log;

import ccc.chess.engines.BuildConfig;
import com.kelseyde.calvin.uci.UCI;
import com.kelseyde.calvin.uci.UCICommand;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;

/**
 * Hosts the real Calvin Java engine. The tiny OEX ELF executable only relays
 * stdin/stdout to this service through an authenticated loopback TCP socket.
 */
public class CalvinEngineService extends Service {

    private static final String TAG = "CalvinEngineService";
    private static final int SOCKET_PORT = 43187;
    private static final String SOCKET_TOKEN = "CalvinOEXBridgeV1-6f9c1a82";
    private static final long EXPECTED_NETWORK_BYTES = 12_589_058L;

    private HandlerThread engineThread;
    private Handler engineHandler;
    private Messenger serviceMessenger;
    private volatile Messenger activeClient;
    private volatile boolean initialized;

    private volatile boolean socketServerRunning;
    private volatile ServerSocket loopbackServerSocket;
    private Thread socketServerThread;
    private volatile Socket activeSocketClient;
    private volatile boolean socketClientConnected;
    private final Object socketOutputLock = new Object();
    private volatile PrintWriter socketOutput;

    @Override
    public void onCreate() {
        super.onCreate();
        engineThread = new HandlerThread("CalvinEngineCommands");
        engineThread.start();
        engineHandler = new Handler(engineThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                handleClientMessage(msg);
            }
        };
        serviceMessenger = new Messenger(engineHandler);

        socketServerRunning = true;
        socketServerThread = new Thread(this::runSocketServer, "CalvinOexUciSocket");
        socketServerThread.setDaemon(true);
        socketServerThread.start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return serviceMessenger.getBinder();
    }

    private void runSocketServer() {
        while (socketServerRunning) {
            ServerSocket server = null;
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(
                        InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), SOCKET_PORT), 1);
                loopbackServerSocket = server;
                Log.i(TAG, "Calvin UCI loopback server listening on 127.0.0.1:" + SOCKET_PORT);
                while (socketServerRunning) {
                    Socket client = server.accept();
                    client.setTcpNoDelay(true);
                    serveSocketClient(client);
                }
            } catch (IOException error) {
                if (socketServerRunning) {
                    Log.e(TAG, "Calvin UCI socket server failed", error);
                    sleepQuietly(500L);
                }
            } finally {
                loopbackServerSocket = null;
                if (server != null) {
                    try { server.close(); } catch (IOException ignored) { }
                }
            }
        }
    }

    private void serveSocketClient(Socket client) {
        PrintWriter writer = null;
        boolean ownsSocketSession = false;
        activeSocketClient = client;
        try {
            // Do not let a local process hold the single-client accept loop indefinitely.
            client.setSoTimeout(5000);
            InputStream clientInput = client.getInputStream();
            if (!authenticateBridge(clientInput)) {
                Log.w(TAG, "Rejected unauthenticated local Calvin bridge connection");
                return;
            }
            writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                    client.getOutputStream(), StandardCharsets.UTF_8)), false);
            if (activeClient != null) {
                writer.println("info string Calvin is busy with another GUI session");
                writer.flush();
                return;
            }
            client.setSoTimeout(0);
            socketClientConnected = true;
            ownsSocketSession = true;
            synchronized (socketOutputLock) {
                socketOutput = writer;
            }

            // Configure the network path before UCI's static Engine/NNUE classes initialize.
            ensureInitialized();

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    clientInput, StandardCharsets.UTF_8));
            String line;
            while (socketServerRunning && (line = reader.readLine()) != null) {
                final String commandLine = line.trim();
                if (commandLine.isEmpty()) continue;
                Handler handler = engineHandler;
                if (handler == null) break;
                handler.post(() -> executeUciCommand(commandLine));
            }
        } catch (Throwable error) {
            Log.e(TAG, "Calvin UCI bridge connection failed", error);
            sendSocketLine("info string Calvin service error: " + safeMessage(error));
        } finally {
            synchronized (socketOutputLock) {
                if (socketOutput == writer) socketOutput = null;
            }
            if (ownsSocketSession) socketClientConnected = false;
            if (ownsSocketSession && initialized && engineHandler != null) {
                engineHandler.post(() -> {
                    try { UCICommand.parse("stop").execute(); }
                    catch (Throwable error) { Log.w(TAG, "Could not stop after bridge disconnect", error); }
                });
            }
            if (activeSocketClient == client) activeSocketClient = null;
            try { client.close(); } catch (IOException ignored) { }
        }
    }

    private boolean authenticateBridge(InputStream input) throws IOException {
        byte[] expected = SOCKET_TOKEN.getBytes(StandardCharsets.US_ASCII);
        byte[] received = new byte[expected.length];
        int offset = 0;
        while (offset < received.length) {
            int count = input.read(received, offset, received.length - offset);
            if (count < 0) return false;
            offset += count;
        }
        return MessageDigest.isEqual(expected, received);
    }

    private void executeUciCommand(String commandLine) {
        try {
            UCICommand.parse(commandLine).execute();
        } catch (Throwable error) {
            Log.e(TAG, "UCI command failed: " + commandLine, error);
            publishError("Calvin command failed: " + safeMessage(error));
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendSocketLine(String line) {
        synchronized (socketOutputLock) {
            PrintWriter writer = socketOutput;
            if (writer == null) return;
            writer.println(line);
            writer.flush();
            if (writer.checkError()) socketOutput = null;
        }
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
                    if (!command.trim().isEmpty()) UCICommand.parse(command).execute();
                    break;
                default:
                    publishError("Unsupported Calvin service message: " + message.what);
                    break;
            }
        } catch (Throwable error) {
            publishError("Calvin engine request failed: " + safeMessage(error));
        }
    }

    private boolean claimController(Messenger requested) {
        if (requested == null) {
            publishError("Calvin client did not provide a reply Messenger");
            return false;
        }
        if (socketClientConnected) {
            sendError(requested, "Calvin is busy with the OEX UCI session");
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

        File networkFile = new File(getFilesDir(), "calvin1024_8b-" + BuildConfig.VERSION_CODE + ".nnue");
        copyBundledNetwork(networkFile);
        System.setProperty("calvin.nnue.path", networkFile.getAbsolutePath());

        // This initializes Calvin's singleton only after the network path is ready.
        UCI.setOutputListener(this::publishOutput);
        UCI.Options.pretty = false;
        UCI.Options.output = true;
        initialized = true;
        Log.i(TAG, "Calvin Java engine initialized; NNUE bytes=" + networkFile.length());
    }

    /**
     * Calvin's loader expects exactly the tensor payload (12,589,058 bytes).
     * The current upstream asset can append 62 metadata/padding bytes. Only
     * the required leading payload is copied; Network.java then validates it.
     */
    private void copyBundledNetwork(File destination) throws IOException {
        if (destination.isFile() && destination.length() == EXPECTED_NETWORK_BYTES) return;

        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        long copied = 0L;
        try (InputStream input = getAssets().open("calvin1024_8b.nnue");
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[32 * 1024];
            while (copied < EXPECTED_NETWORK_BYTES) {
                int wanted = (int) Math.min(buffer.length, EXPECTED_NETWORK_BYTES - copied);
                int count = input.read(buffer, 0, wanted);
                if (count < 0) {
                    throw new IOException("Bundled Calvin NNUE is truncated: " + copied
                            + " bytes; expected " + EXPECTED_NETWORK_BYTES);
                }
                output.write(buffer, 0, count);
                copied += count;
            }
            output.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }

        if (temporary.length() != EXPECTED_NETWORK_BYTES) {
            long actual = temporary.length();
            temporary.delete();
            throw new IOException("Invalid Calvin NNUE payload: " + actual + " bytes");
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
        sendSocketLine(line);
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
        sendSocketLine("info string " + line.replace('\n', ' ').replace('\r', ' '));
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
        socketServerRunning = false;
        ServerSocket server = loopbackServerSocket;
        if (server != null) {
            try { server.close(); } catch (IOException ignored) { }
        }
        Socket client = activeSocketClient;
        if (client != null) {
            try { client.close(); } catch (IOException ignored) { }
        }
        synchronized (socketOutputLock) {
            socketOutput = null;
        }
        if (initialized) {
            UCI.setOutputListener(line -> { });
            try { UCICommand.parse("quit").execute(); }
            catch (Throwable error) { Log.w(TAG, "Calvin shutdown failed", error); }
            UCI.setOutputListener(null);
        }
        if (engineThread != null) engineThread.quitSafely();
        activeClient = null;
        super.onDestroy();
    }
}
