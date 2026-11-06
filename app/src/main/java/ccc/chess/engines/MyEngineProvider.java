package ccc.chess.engines;

import com.kalab.chess.enginesupport.ChessEngineProvider;

/**
 * Standard OEX provider. It only publishes the executable stored in
 * ApplicationInfo.nativeLibraryDir; it does not host or start the engine.
 * The OEX GUI launches libcalvin.so as its own child process.
 */
public class MyEngineProvider extends ChessEngineProvider {

    @Override
    public boolean onCreate() {
        return super.onCreate();
    }
}
