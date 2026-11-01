package com.kelseyde.calvin.android;

/** Message contract used by Android chess applications that want to embed Calvin without an ELF engine file. */
public final class CalvinEngineProtocol {
    private CalvinEngineProtocol() { }

    public static final String PACKAGE_NAME = "ccc.chess.engines";
    public static final String ACTION_BIND = "ccc.chess.engines.action.BIND_CALVIN_ENGINE";

    public static final int MSG_REGISTER_CLIENT = 1;
    public static final int MSG_UNREGISTER_CLIENT = 2;
    public static final int MSG_COMMAND = 3;
    public static final int MSG_OUTPUT = 4;
    public static final int MSG_ERROR = 5;

    public static final String KEY_COMMAND = "command";
    public static final String KEY_LINE = "line";
}
