Vendored LibADB Android 3.1.1, upstream commit c849886ebc6d48e7b46d967e78a6bb65c90c3b74.

Source: https://github.com/MuntashirAkon/libadb-android/tree/3.1.1

Use the Apache-2.0 alternative where offered. Original per-file SPDX notices and
upstream licenses are retained in LICENSES/.

Local changes: pairing has an eight-second total socket watchdog and closes
partially initialized resources; TCP connect and TLS handshake are bounded;
ADB stream open handles early OKAY replies and has a deadline; stream write-ready
waits have a deadline. No hidden API policy or global security setting is changed.
Runtime TLS uses the explicitly packaged Conscrypt provider.

Dependencies: androidx.annotation 1.9.1, bcprov-jdk15to18 1.81,
spake2-android 2.2.1, conscrypt-android 2.5.3. Published SPAKE2 and Conscrypt AARs
were inspected: every supported ABI has PT_LOAD alignment 0x4000.
