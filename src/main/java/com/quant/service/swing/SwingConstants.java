package com.quant.service.swing;

public final class SwingConstants {

  private SwingConstants() {}

  // watch status
  public static final String WATCH_WATCHING = "WATCHING";
  public static final String WATCH_SETUP_PULLBACK = "SETUP_PULLBACK";
  public static final String WATCH_SETUP_BREAKOUT = "SETUP_BREAKOUT";
  public static final String WATCH_ENTRY_SIGNALED = "ENTRY_SIGNALED";
  public static final String WATCH_HOLDING = "HOLDING";
  public static final String WATCH_HOLDING_PARTIAL = "HOLDING_PARTIAL";
  public static final String WATCH_CLOSED = "CLOSED";
  public static final String WATCH_PAUSED = "PAUSED";
  public static final String WATCH_FILTERED = "FILTERED";

  // setup
  public static final String SETUP_PULLBACK = "PULLBACK";
  public static final String SETUP_BREAKOUT = "BREAKOUT";
  public static final String SETUP_ACTIVE = "ACTIVE";
  public static final String SETUP_TRIGGERED = "TRIGGERED";
  public static final String SETUP_EXPIRED = "EXPIRED";
  public static final String SETUP_INVALID = "INVALID";

  // position
  public static final String POS_OPEN = "OPEN";
  public static final String POS_PARTIAL = "PARTIAL";
  public static final String POS_CLOSED = "CLOSED";

  // signal types
  public static final String SIG_ENTRY_PULLBACK = "ENTRY_PULLBACK";
  public static final String SIG_ENTRY_BREAKOUT = "ENTRY_BREAKOUT";
  public static final String SIG_ADD = "ADD";
  public static final String SIG_STOP_HARD = "STOP_HARD";
  public static final String SIG_STOP_SOFT = "STOP_SOFT";
  public static final String SIG_TRAIL_TP = "TRAIL_TP";
  public static final String SIG_MA20_EXIT = "MA20_EXIT";
  public static final String SIG_DEATH_CROSS = "DEATH_CROSS";
  public static final String SIG_CRASH_HALVE = "CRASH_HALVE";
  public static final String SIG_FILTER_WARN = "FILTER_WARN";
  public static final String SIG_SETUP_DETECTED = "SETUP_DETECTED";

  public static final String LEVEL_INFO = "INFO";
  public static final String LEVEL_ACTION = "ACTION";
  public static final String LEVEL_CRITICAL = "CRITICAL";

  public static final String SIGNAL_PENDING = "PENDING";
  public static final String SIGNAL_NOTIFIED = "NOTIFIED";
  public static final String SIGNAL_EXECUTED = "EXECUTED";
  public static final String SIGNAL_ACKED = "ACKED";

  public static final String SIDE_BUY = "BUY";
  public static final String SIDE_SELL = "SELL";

  public static final String SRC_AUTO = "AUTO";
  public static final String SRC_USER = "USER_CONFIRM";

  public static final String MODE_HYBRID = "HYBRID";
}
