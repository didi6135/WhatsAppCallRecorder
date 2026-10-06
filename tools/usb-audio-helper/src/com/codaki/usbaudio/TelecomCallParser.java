package com.codaki.usbaudio;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded current CallsManager/foreground metadata extraction. Only primitive proof leaves this
 * parser; temporary identifiers link current rows and are discarded by finish or any failure.
 * Canonical AOSP Android14/15/16: Telecomm Call.toString, CallsManager.dump and
 * CallAudioManager.dump. Unknown OEM layouts must be verified before adding support.
 */
public final class TelecomCallParser {
  public static final int MAX_DUMP_BYTES = 2 * 1024 * 1024;
  public static final int MAX_DUMP_LINES = 32768;
  public static final int MAX_LINE_BYTES = 4096;
  public static final int MAX_CURRENT_CALLS = 64;
  public static final int MAX_USER_ID = 21474;
  public static final int PACKAGE_NONE = 0, PACKAGE_WHATSAPP = 1,
      PACKAGE_BUSINESS = 2, PACKAGE_OTHER = 3;
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_@.#:-]{1,128}");
  private static final Pattern SAMSUNG_PREAMBLE = Pattern.compile(
      "telecom: samsung/[A-Za-z0-9_.-]{1,64}/[A-Za-z0-9_.-]{1,64}:(14|15|16)/"
      + "[A-Za-z0-9_.-]{1,96}/[A-Za-z0-9_.-]{1,96}:user/release-keys, \\1, "
      + "/system/priv-app/Telecom, [0-9]{1,20}\\[ SYSTEM \\], [0-9]{1,20}, [0-9]{1,20}");
  private static final Pattern ACCOUNT = Pattern.compile(
      "ComponentInfo\\{([A-Za-z][A-Za-z0-9_.]{0,255})/([A-Za-z0-9_.$]{1,256})\\}, ([^,\\r\\n]{1,256}), UserHandle\\{([0-9]{1,10})\\}");
  private static final String ROW_BODY =
      "\\[Call id=([^,]{1,128}), state=([A-Z_]{1,32}), tpac=(.{1,800}?), cmgr=(.{1,800}?), "
      + "handle=([^,]{1,256}), vidst=(AT?R?P?), childs\\(([0-9]{1,10})\\), "
      + "has_parent\\((true|false)\\), cap=(\\[[^\\[\\]]{0,512}\\]), prop=(\\[[^\\[\\]]{0,256}\\])\\]";
  private static final Pattern ROW = Pattern.compile(ROW_BODY + ", voip=(true|false)");
  private static final Pattern SAMSUNG_ROW = Pattern.compile(ROW_BODY
      + ", voip=(true|false)caps=(\\[Capabilities:[^\\[\\]]{0,1800}\\]), "
      + "props=(\\[Properties:[^\\[\\]]{0,1024}\\]), conf\\((true|false)\\), had_child\\((true|false)\\)");
  private static final Pattern ROW_WITH_SUFFIX = Pattern.compile(ROW_BODY + "(.*)");
  private static final Pattern DIAGNOSTIC_ACCOUNT = Pattern.compile(
      "ComponentInfo\\{([^/{}]{1,256})/([^{}]{1,256})\\}, ([^,\\r\\n]{1,256}), UserHandle\\{([^{}]{1,10})\\}");
  private static final Set<String> STATES = set("NEW", "CONNECTING", "SELECT_PHONE_ACCOUNT",
      "DIALING", "RINGING", "ACTIVE", "ON_HOLD", "DISCONNECTED", "ABORTED", "DISCONNECTING",
      "PULLING", "ANSWERED", "AUDIO_PROCESSING", "SIMULATED_RINGING");
  private static final Set<String> PROPERTIES = set("self_mng", "ecbm", "HD", "wifi", "gen_conf",
      "xtrnl", "priv", "rtt", "ecall", "remote_hst", "adhoc_conf", "dngrd_conf", "xsim");
  private static final Set<String> CAPABILITIES = set("hld", "sup_hld", "mrg_cnf", "swp_cnf",
      "txt", "mut", "mng_cnf", "VTlrx", "VTltx", "VTlbi", "VTrrx", "VTrtx", "VTrbi",
      "!v2a", "spd_aud", "a2v", "paus_VT", "1p_cnf", "rsp_by_con", "pull", "sup_def",
      "add_participant", "sup_trans", "sup_cTrans", "sup_rtt");
  // Connection.capabilities/propertiesToStringInternal canonical Android 16 long/short order.
  private static final String[] LONG_CAPABILITIES = {
      "CAPABILITY_HOLD:hld", "CAPABILITY_SUPPORT_HOLD:sup_hld", "CAPABILITY_MERGE_CONFERENCE:mrg_cnf",
      "CAPABILITY_SWAP_CONFERENCE:swp_cnf", "CAPABILITY_RESPOND_VIA_TEXT:txt", "CAPABILITY_MUTE:mut",
      "CAPABILITY_MANAGE_CONFERENCE:mng_cnf", "CAPABILITY_SUPPORTS_VT_LOCAL_RX:VTlrx",
      "CAPABILITY_SUPPORTS_VT_LOCAL_TX:VTltx", "CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL:VTlbi",
      "CAPABILITY_SUPPORTS_VT_REMOTE_RX:VTrrx", "CAPABILITY_SUPPORTS_VT_REMOTE_TX:VTrtx",
      "CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL:VTrbi", "CAPABILITY_CANNOT_DOWNGRADE_VIDEO_TO_AUDIO:!v2a",
      "CAPABILITY_SPEED_UP_MT_AUDIO:spd_aud", "CAPABILITY_CAN_UPGRADE_TO_VIDEO:a2v",
      "CAPABILITY_CAN_PAUSE_VIDEO:paus_VT", "CAPABILITY_SINGLE_PARTY_CONFERENCE:1p_cnf",
      "CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION:rsp_by_con", "CAPABILITY_CAN_PULL_CALL:pull",
      "CAPABILITY_SUPPORT_DEFLECT:sup_def", "CAPABILITY_ADD_PARTICIPANT:add_participant",
      "CAPABILITY_TRANSFER:sup_trans", "CAPABILITY_TRANSFER_CONSULTATIVE:sup_cTrans",
      "CAPABILITY_REMOTE_PARTY_SUPPORTS_RTT:sup_rtt"};
  private static final String[] LONG_PROPERTIES = {
      "PROPERTY_SELF_MANAGED:self_mng", "PROPERTY_EMERGENCY_CALLBACK_MODE:ecbm",
      "PROPERTY_HIGH_DEF_AUDIO:HD", "PROPERTY_WIFI:wifi", "PROPERTY_GENERIC_CONFERENCE:gen_conf",
      "PROPERTY_IS_EXTERNAL_CALL:xtrnl", "PROPERTY_HAS_CDMA_VOICE_PRIVACY:priv", "PROPERTY_IS_RTT:rtt",
      "PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL:ecall", "PROPERTY_REMOTELY_HOSTED:remote_hst",
      "PROPERTY_IS_ADHOC_CONFERENCE:adhoc_conf", "PROPERTY_IS_DOWNGRADED_CONFERENCE:dngrd_conf",
      "PROPERTY_CROSS_SIM:xsim"};
  private static final String[] AUDIO_COLLECTIONS = {"All calls:",
      "Active dialing, or connecting calls:", "Ringing calls:", "Holding calls:"};
  private static final int MANAGER = 0, CURRENT_HEADER = 1, CURRENT = 2, AUDIO = 3,
      FOREGROUND_VALUE = 4, FOREGROUND_BOUNDARY = 5, COMPLETE = 6;

  public static final class State {
    public static final State UNKNOWN = new State(false, PACKAGE_NONE, -1, -1, -1, false, false, false);
    public final boolean known, foregroundMatched, selfManaged, voip;
    public final int packageCode, userId, liveCalls, activeCalls;
    private State(boolean known, int packageCode, int userId, int liveCalls, int activeCalls,
        boolean foregroundMatched, boolean selfManaged, boolean voip) {
      this.known = known; this.packageCode = packageCode; this.userId = userId;
      this.liveCalls = liveCalls; this.activeCalls = activeCalls;
      this.foregroundMatched = foregroundMatched; this.selfManaged = selfManaged; this.voip = voip;
    }
  }

  /** Retained only while comparing current metadata; never logged or exposed. */
  private static final class Row {
    final String id, packageName, state;
    final int userId;
    final boolean selfManaged, voip;
    Row(String id, String packageName, String state, int userId, boolean selfManaged, boolean voip) {
      this.id = id; this.packageName = packageName; this.state = state; this.userId = userId;
      this.selfManaged = selfManaged; this.voip = voip;
    }
    boolean live() { return !state.equals("DISCONNECTED") && !state.equals("ABORTED"); }
    boolean same(Row other) {
      return id.equals(other.id) && packageName.equals(other.packageName) && state.equals(other.state)
          && userId == other.userId && selfManaged == other.selfManaged && voip == other.voip;
    }
  }

  private final StringBuilder line = new StringBuilder();
  private final List<Row> current = new ArrayList<>();
  private final int sdk;
  private int byteCount, lineCount, failureCode, rowFailureCode, rowTailFlags, rowCharFlags, stage = MANAGER, managerIndent = -1, currentIndent = -1,
      audioIndent = -1, collectionIndex;
  private boolean invalid, finished, pendingCarriageReturn, sawAudioProcessingCollection, sawSamsungPreamble;
  private Row foreground;
  private State finishedState;

  /** Existing host/API callers keep the proven Android16 grammar by default. */
  public TelecomCallParser() { this(36); }
  public TelecomCallParser(int sdk) { this.sdk = sdk; if (sdk < 34) fail(100); }

  /** False instructs the reader to terminate immediately; no partial result is usable. */
  public boolean consume(byte[] bytes, int offset, int length) {
    if (finished) { fail(); finishedState = State.UNKNOWN; return false; }
    if (invalid) return false;
    if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
    if (length > MAX_DUMP_BYTES - byteCount) { fail(10); return false; }
    byteCount += length;
    for (int i = offset; i < offset + length; i++) {
      int value = bytes[i] & 255;
      if (pendingCarriageReturn && value != '\n') { fail(13); return false; }
      if (value == '\r') { pendingCarriageReturn = true; continue; }
      pendingCarriageReturn = false;
      if (value == '\n') {
        if (++lineCount > MAX_DUMP_LINES) { fail(11); return false; }
        acceptLine(line.toString()); line.setLength(0);
      } else {
        if (value == 0 || (value < 32 && value != '\t') || value == 127) { fail(13); return false; }
        if (line.length() >= MAX_LINE_BYTES) { fail(12); return false; }
        line.append((char)value);
      }
      if (invalid) { line.setLength(0); return false; }
    }
    return true;
  }

  /** A complete current set, explicit foreground, and state-machine boundary are mandatory. */
  public State finish() {
    if (finished) return finishedState;
    if (!invalid && line.length() != 0) {
      if (++lineCount > MAX_DUMP_LINES) fail(11); else acceptLine(line.toString());
    }
    State result = State.UNKNOWN;
    if (!invalid && stage == COMPLETE) {
      int live = 0, active = 0;
      Row unique = null;
      for (Row row : current) {
        if (row.live()) { live++; unique = row; }
        if (row.state.equals("ACTIVE")) active++;
      }
      boolean matched = false;
      if (foreground != null) {
        for (Row row : current) if (row.same(foreground) && row.live()) matched = true;
      }
      if ((foreground == null || matched) && (live != 0 || foreground == null)) {
        int pkg = live == 1 ? packageCode(unique.packageName) : PACKAGE_NONE;
        result = new State(true, pkg, live == 1 ? unique.userId : -1, live, active, matched,
            live == 1 && unique.selfManaged, live == 1 && unique.voip);
      } else failureCode = 200;
    }
    if (!invalid && stage != COMPLETE) failureCode = 201;
    current.clear(); foreground = null; line.setLength(0);
    finished = true; finishedState = result;
    return result;
  }

  public static State parse(String dump) {
    return parse(dump, 36);
  }
  public static State parse(String dump, int sdk) {
    if (dump == null || dump.length() > MAX_DUMP_BYTES) return State.UNKNOWN;
    byte[] bytes = dump.getBytes(StandardCharsets.UTF_8);
    TelecomCallParser parser = new TelecomCallParser(sdk);
    return parser.consume(bytes, 0, bytes.length) ? parser.finish() : State.UNKNOWN;
  }

  /** Fixed, privacy-safe diagnostic: 0 success; 10 bytes, 11 lines, 12 line size, 13 controls;
   * 100..105 unexpected manager/current/audio/foreground structure; 200 linkage, 201 truncation.
   * This carries neither identifiers nor any text from the dump and cannot authorize capture. */
  public int diagnosticFailureCode() { return failureCode; }
  /** Fixed row diagnostics only, for the first failed current/foreground row; no row text retained. */
  public int diagnosticRowFailureCode() { return rowFailureCode; }
  public int diagnosticRowTailFlags() { return rowTailFlags; }
  public int diagnosticRowCharFlags() { return rowCharFlags; }

  private void acceptLine(String original) {
    // History, state-machine traces and later services cannot supply or alter current evidence.
    if (stage == COMPLETE) return;
    String value = original.trim();
    if (value.isEmpty()) return;
    int indent = indentation(original);
    if (indent < 0) { fail(); return; }
    switch (stage) {
      case MANAGER:
        // Exact Samsung16 wrapper shape;14/15 are candidates with real-device testing pending.
        // Bind both release fields to the helper's actual SDK and accept once only.
        // Build strings remain transient and cannot appear in State or diagnostics.
        Matcher wrapper = SAMSUNG_PREAMBLE.matcher(value);
        if (!sawSamsungPreamble && indent == 0 && wrapper.matches()
            && Integer.parseInt(wrapper.group(1)) == sdk - 20) {
          sawSamsungPreamble = true; return;
        }
        if (!value.equals("CallsManager:") || (sawSamsungPreamble && indent != 0)) { fail(); return; }
        managerIndent = indent; stage = CURRENT_HEADER; return;
      case CURRENT_HEADER:
        if (!value.equals("mCalls:") || indent <= managerIndent) { fail(); return; }
        currentIndent = indent; stage = CURRENT; return;
      case CURRENT:
        if (value.equals("mCallAudioManager:") && indent == currentIndent) { stage = AUDIO; return; }
        if (indent <= currentIndent || current.size() >= MAX_CURRENT_CALLS) { fail(); return; }
        Row row = parseRow(value);
        if (row == null) { captureRowFailure(value); fail(); return; }
        for (Row existing : current) if (existing.id.equals(row.id)) { fail(); return; }
        current.add(row); return;
      case AUDIO:
        if (audioIndent < 0) audioIndent = indent;
        if (audioIndent <= currentIndent) { fail(); return; }
        if (indent > audioIndent) {
          if (collectionIndex == 0 || !ID.matcher(value).matches()) fail();
          return; // Collection IDs are deliberately neither parsed nor retained.
        }
        if (indent != audioIndent) { fail(); return; }
        if (collectionIndex < AUDIO_COLLECTIONS.length && value.equals(AUDIO_COLLECTIONS[collectionIndex])) {
          collectionIndex++; return;
        }
        // Verified Samsung Android 16 dump adds this optional collection after Holding calls.
        // Like the other audio collections, its IDs never affect the authoritative current set.
        if (collectionIndex == AUDIO_COLLECTIONS.length && !sawAudioProcessingCollection
            && value.equals("AudioProcessing calls:")) {
          sawAudioProcessingCollection = true; return;
        }
        if (collectionIndex == AUDIO_COLLECTIONS.length && value.equals("Foreground call:")) {
          stage = FOREGROUND_VALUE; return;
        }
        fail(); return;
      case FOREGROUND_VALUE:
        if (indent != audioIndent) { fail(); return; }
        if (!value.equals("null")) {
          foreground = parseRow(value);
          if (foreground == null) { captureRowFailure(value); fail(); return; }
        }
        stage = FOREGROUND_BOUNDARY; return;
      case FOREGROUND_BOUNDARY:
        if (indent != audioIndent || !value.equals("CallAudioModeStateMachine:")) { fail(); return; }
        stage = COMPLETE; return;
      default: fail();
    }
  }

  private static Row parseRow(String value) {
    if (diagnosticRowCode(value) != 0) return null;
    Matcher row = matchedRow(value);
    String packageName = "";
    int userId = -1;
    if (!row.group(3).equals("null")) {
      Matcher account = ACCOUNT.matcher(row.group(3));
      account.matches(); userId = integer(account.group(4));
      packageName = account.group(1);
    }
    return new Row(row.group(1), packageName, row.group(2), userId,
        Arrays.asList(row.group(10).substring(1, row.group(10).length() - 1).trim().split(" +")).contains("self_mng"),
        row.group(11).equals("true"));
  }
  /** Diagnostic only: 0 valid; 1 size/characters; 2 grammar; 3 ID; 4 state; 5 children;
   * 6 capabilities; 7 properties; 8 target account; 9 target user; 10 target package;
   * 11 manager account; 12 manager user; 13 full capabilities mismatch;
   * 14 full properties mismatch. Never changes parser acceptance. */
  public static int diagnosticRowCode(String value) {
    if (!boundedAsciiRow(value)) return 1;
    Matcher row = matchedRow(value);
    if (row == null) return 2;
    if (!ID.matcher(row.group(1)).matches()) return 3;
    if (!STATES.contains(row.group(2))) return 4;
    if (integer(row.group(7)) < 0) return 5;
    if (!validTokens(row.group(9), CAPABILITIES)) return 6;
    if (!validTokens(row.group(10), PROPERTIES)) return 7;
    if (row.groupCount() > 11) {
      if (!matchingCanonicalTokens(row.group(12), "Capabilities:", row.group(9), LONG_CAPABILITIES)) return 13;
      if (!matchingCanonicalTokens(row.group(13), "Properties:", row.group(10), LONG_PROPERTIES)) return 14;
    }
    if (!row.group(3).equals("null")) {
      Matcher account = ACCOUNT.matcher(row.group(3));
      if (!account.matches()) return 8;
      int user = integer(account.group(4));
      if (user < 0 || user > MAX_USER_ID) return 9;
      if (!account.group(1).matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")) return 10;
    }
    if (!row.group(4).equals("null")) {
      Matcher manager = ACCOUNT.matcher(row.group(4));
      if (!manager.matches()) return 11;
      int user = integer(manager.group(4));
      if (user < 0 || user > MAX_USER_ID) return 12;
    }
    return 0;
  }
  /** Diagnostic bitmask only: 1 fixed VoIP prefix, 2 true bool, 4 false bool,
   * 8 exact bounded body, 16 exact full-row grammar, 32 extra trailing suffix.
   * Unknown suffix text is never returned or retained and never authorizes capture. */
  public static int diagnosticRowTailFlags(String value) {
    if (!boundedPrintableRow(value)) return 0;
    int characters = diagnosticRowCharFlags(value);
    if ((characters & 1) != 0 && (characters & (16 | 32 | 64)) != 0) return 0;
    Matcher row = ROW_WITH_SUFFIX.matcher(value);
    boolean bodyMatched = row.matches();
    int flags = bodyMatched ? 8 : 0;
    Matcher recognized = matchedRow(value);
    if (recognized != null) flags |= 16;
    int marker = value.indexOf(", voip=");
    if (marker >= 0 && marker == value.lastIndexOf(", voip=")) {
      flags |= 1;
      String bool = value.substring(marker + 7);
      if (recognized != null && recognized.groupCount() > 11)
        flags |= (recognized.group(11).equals("true") ? 2 : 4) | 32;
      if (bool.equals("true") || bool.startsWith("true, ")) flags |= 2;
      if (bool.equals("false") || bool.startsWith("false, ")) flags |= 4;
      if (((flags & 2) != 0 && !bool.equals("true"))
          || ((flags & 4) != 0 && !bool.equals("false"))) flags |= 32;
    } else if (bodyMatched && !row.group(11).isEmpty()) flags |= 32;
    return flags;
  }
  private static Matcher matchedRow(String value) {
    Matcher row = ROW.matcher(value);
    if (row.matches()) return row;
    row = SAMSUNG_ROW.matcher(value);
    return row.matches() ? row : null;
  }
  private static boolean matchingCanonicalTokens(String full, String label, String shortValue, String[] mapping) {
    String text = full.substring(label.length() + 1, full.length() - 1).trim();
    StringBuilder mapped = new StringBuilder();
    int previous = -1;
    if (!text.isEmpty()) for (String token : text.split(" +")) {
      int found = -1;
      for (int i = 0; i < mapping.length; i++)
        if (mapping[i].substring(0, mapping[i].indexOf(':')).equals(token)) { found = i; break; }
      if (found <= previous) return false; // Unknown, duplicate, or noncanonical order.
      if (mapped.length() > 0) mapped.append(' ');
      mapped.append(mapping[found].substring(mapping[found].indexOf(':') + 1)); previous = found;
    }
    return mapped.toString().equals(shortValue.substring(1, shortValue.length() - 1).trim().replaceAll(" +", " "));
  }
  /** Diagnostic only: 1 non-ASCII; 2 target opaque ID; 4 manager opaque ID; 8 handle;
   * 16 critical field; 32 ambiguous boundaries; 64 ASCII control. No acceptance changes.
   * Opaque-only means bit1 plus opaque bits, without16/32/64; strings never leave this method. */
  public static int diagnosticRowCharFlags(String value) {
    if (value == null || value.length() > MAX_LINE_BYTES) return 32;
    int flags = hasNonAscii(value) ? 1 : 0;
    if (!boundedPrintableRow(value)) flags |= 64;
    if ((flags & 1) == 0) return flags;
    String[] markers = {"[Call id=", ", state=", ", tpac=", ", cmgr=", ", handle=",
        ", vidst=", ", childs(", "), has_parent(", "), cap=", ", prop="};
    for (String marker : markers) {
      int index = value.indexOf(marker);
      if (index < 0 || index != value.lastIndexOf(marker)) return flags | 16 | 32;
    }
    Matcher row = ROW_WITH_SUFFIX.matcher(value);
    if (!row.matches()) return flags | 16 | 32;
    flags |= diagnosticAccountCharFlags(row.group(3), 2);
    flags |= diagnosticAccountCharFlags(row.group(4), 4);
    if (hasNonAscii(row.group(5))) flags |= 8;
    for (int group : new int[] {1, 2, 6, 7, 8, 9, 10, 11})
      if (hasNonAscii(row.group(group))) flags |= 16;
    return flags;
  }
  private static int diagnosticAccountCharFlags(String value, int opaqueBit) {
    if (!hasNonAscii(value)) return 0;
    Matcher account = DIAGNOSTIC_ACCOUNT.matcher(value);
    if (!account.matches()) return 16 | 32;
    int flags = hasNonAscii(account.group(3)) ? opaqueBit : 0;
    for (int group : new int[] {1, 2, 4}) if (hasNonAscii(account.group(group))) flags |= 16;
    return flags;
  }
  private static boolean hasNonAscii(String value) {
    for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 127) return true;
    return false;
  }
  private static boolean boundedPrintableRow(String value) {
    if (value == null || value.length() > MAX_LINE_BYTES) return false;
    for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) == 127) return false;
    return true;
  }
  private static boolean boundedAsciiRow(String value) {
    if (value == null || value.length() > MAX_LINE_BYTES) return false;
    for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) > 126) return false;
    return true;
  }
  private void captureRowFailure(String value) {
    rowFailureCode = diagnosticRowCode(value); rowTailFlags = diagnosticRowTailFlags(value);
    rowCharFlags = diagnosticRowCharFlags(value);
  }
  private static boolean validTokens(String value, Set<String> allowed) {
    String tokens = value.substring(1, value.length() - 1).trim();
    if (tokens.isEmpty()) return true;
    Set<String> seen = new HashSet<>();
    for (String token : tokens.split(" +")) if (!allowed.contains(token) || !seen.add(token)) return false;
    return true;
  }
  private static int indentation(String value) {
    int count = 0;
    while (count < value.length() && value.charAt(count) == ' ') count++;
    return count < value.length() && value.charAt(count) == '\t' ? -1 : count;
  }
  private static int integer(String value) {
    try { return Integer.parseInt(value); } catch (NumberFormatException invalid) { return -1; }
  }
  private static int packageCode(String value) {
    if (value.isEmpty()) return PACKAGE_NONE;
    if (value.equals("com.whatsapp")) return PACKAGE_WHATSAPP;
    if (value.equals("com.whatsapp.w4b")) return PACKAGE_BUSINESS;
    return PACKAGE_OTHER;
  }
  private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
  private void fail() { fail(100 + stage); }
  private void fail(int code) { failureCode = code; invalid = true; line.setLength(0); current.clear(); foreground = null; }
}
