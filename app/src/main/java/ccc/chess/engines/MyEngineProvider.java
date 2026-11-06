package ccc.chess.engines;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import com.kalab.chess.enginesupport.ChessEngineProvider;
import com.kelseyde.calvin.android.CalvinEngineService;

/**
 * Standard OEX content provider. Binding the local Calvin service makes its
 * UCI socket available before DroidFish launches the tiny native relay.
 */
public class MyEngineProvider extends ChessEngineProvider {

    private static final String TAG = "CalvinOexProvider";
    private ServiceConnection calvinConnection;
    private boolean calvinBound;

    @Override
    public boolean onCreate() {
        boolean created = super.onCreate();
        Context context = getContext();
        if (context == null) {
            Log.e(TAG, "Provider has no Context; Calvin service was not started");
            return created;
        }

        calvinConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, "Calvin Java service connected; OEX bridge is ready");
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "Calvin Java service disconnected");
            }
        };

        try {
            Intent serviceIntent = new Intent(context, CalvinEngineService.class);
            calvinBound = context.bindService(
                    serviceIntent, calvinConnection, Context.BIND_AUTO_CREATE);
            if (!calvinBound) {
                Log.e(TAG, "Unable to bind Calvin Java service");
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to start Calvin Java service", error);
        }
        return created;
    }

    @Override
    public void shutdown() {
        Context context = getContext();
        if (context != null && calvinBound && calvinConnection != null) {
            try {
                context.unbindService(calvinConnection);
            } catch (IllegalArgumentException ignored) {
                // The system may already have released the provider connection.
            }
        }
        calvinBound = false;
        super.shutdown();
    }
}
