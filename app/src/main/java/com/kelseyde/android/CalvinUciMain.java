package com.kelseyde.calvin.android;

import com.kelseyde.calvin.uci.UCI;

import java.io.File;

/**
 * Standalone ART entrypoint launched by libcalvin.so through app_process.
 * The OEX GUI copies and launches the executable in its own engine directory;
 * the launcher extracts this runtime's DEX, NNUE weights, and NEON JNI library
 * into that same GUI-owned directory before starting ART.
 */
public final class CalvinUciMain {

    private CalvinUciMain() { }

    public static void main(String[] args) {
        String nnuePath = System.getenv("CALVIN_NNUE_PATH");
        String neonPath = System.getenv("CALVIN_NEON_LIBRARY");

        if (nnuePath == null || nnuePath.isEmpty()
                || neonPath == null || neonPath.isEmpty()) {
            System.err.println("Calvin: standalone launcher did not provide NNUE/NEON paths");
            return;
        }

        File nnue = new File(nnuePath);
        File neon = new File(neonPath);
        if (!nnue.isFile() || !nnue.canRead()) {
            System.err.println("Calvin: extracted NNUE network is not readable: " + nnuePath);
            return;
        }
        if (!neon.isFile() || !neon.canRead()) {
            System.err.println("Calvin: extracted NEON library is not readable: " + neonPath);
            return;
        }

        System.setProperty("calvin.nnue.path", nnue.getAbsolutePath());
        System.setProperty("calvin.neon.path", neon.getAbsolutePath());

        // Force JNI load before UCI initializes the engine/network. A failure
        // remains non-fatal, and the UCI handshake explains scalar fallback.
        try {
            Class.forName("com.kelseyde.calvin.evaluation.CalvinNeon", true,
                    CalvinUciMain.class.getClassLoader());
        } catch (ClassNotFoundException error) {
            System.err.println("Calvin: NNUE acceleration class missing: " + error);
        } catch (LinkageError error) {
            System.err.println("Calvin: NNUE acceleration initialization failed: " + error);
        }

        System.err.println("Calvin: standalone ART engine process started");
        UCI.run(args);
    }
}
