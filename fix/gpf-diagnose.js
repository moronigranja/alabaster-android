/* Alabaster Dawn - controller fix.
 *
 * Two problems, two mechanisms.
 *
 * 1) Wrong decode table (all platforms)
 *    terra/dist/bundle.js decodes pads like this:
 *
 *      getGamepadMapping(gamepad) {
 *          if (gamepad.mapping != "standard" && g_engine.os == LINUX)
 *              return GAMEPAD_MAPPING_LINUX_WEIRD;   // raw DirectInput/hat layout
 *          return GAMEPAD_MAPPING_STANDARD;          // W3C standard layout
 *      }
 *
 *    A pad that Chromium reports with mapping != "standard" is therefore decoded
 *    with the raw layout on Linux and with the standard layout everywhere else
 *    (Windows, i.e. Wine: GameNative / Winlator / Proton on Android), where the
 *    standard table is wrong for it. This shim hands the game pads that really
 *    are in the W3C standard order, so the standard table is always correct.
 *
 * 2) Chromium never receives a pad at all (GameNative / Winlator on Android)
 *    On Windows Chromium reads gamepads only through XInput
 *    (device/gamepad/xinput_data_fetcher_win.cc hardcodes "xinput1_4.dll").
 *    GameNative feeds Wine a virtual pad, but it does not reach Wine's XInput
 *    device list - so navigator.getGamepads() stays empty and no JS-level
 *    re-mapping can help. GameNative does, however, keep the live pad state in a
 *    64-byte shared-memory struct (WinHandler.gamepad.mem, written per button,
 *    stick and trigger). This shim reads that struct directly when Chromium
 *    reports no pads and synthesizes a standard-layout gamepad from it.
 *
 *    Layout (little endian, 64 bytes) - from GameNative's WinHandler.java:
 *      0  u32  seq            4  i16 LX      6  i16 LY     8  i16 RX   10 i16 RY
 *      12 i16 LT(0..1 -> -32767..32767)      14 i16 RT
 *      16 15 x u8 SDL button states (SDL order: A B X Y BACK GUIDE START L3 R3
 *                 LB RB DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT)
 *      31 u8  POV hat         32 i16 rumbleLow   34 i16 rumbleHigh
 *      40 i32 connected
 *
 * Loaded from terra/index.html (before or after dist/bundle.js - the game looks
 * navigator.getGamepads up on every frame).
 */
(function () {
    'use strict';
    try {

    var cfg = {
        mapping: 'auto',    // auto | standard | dinput
        padIndex: null,     // null = first pad Chromium reports
        shm: 'auto',        // auto | off | full path to gamepad.mem
        gnUdp: 'auto',      // auto | off - ask GameNative's UDP pad server (port 7947)
        gnUdpDelayMs: 5000, // wait this long after boot before opening the socket
        shmButtonTest: false,
        debug: false
    };

    try {
        var fs = require('fs');
        var path = require('path');
        var cfgPath = path.join(__dirname, 'gamepad-fix.json');
        if (fs.existsSync(cfgPath)) {
            var user = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
            for (var k in user)
                if (Object.prototype.hasOwnProperty.call(user, k)) cfg[k] = user[k];
        }
    } catch (e) { /* no node or no config file: defaults are fine */ }

    var rawGetGamepads = navigator.getGamepads ? navigator.getGamepads.bind(navigator) : null;
    var MAX_PADS = 4;
    var AXIS_COUNT = 4;
    var BUTTON_COUNT = 17;

    var pool = [];
    for (var p = 0; p < MAX_PADS; p++) {
        var pad = {
            index: p, id: '', connected: true, mapping: 'standard', timestamp: 0,
            axes: new Array(AXIS_COUNT), buttons: new Array(BUTTON_COUNT)
        };
        for (var a = 0; a < AXIS_COUNT; a++) pad.axes[a] = 0;
        for (var b = 0; b < BUTTON_COUNT; b++) pad.buttons[b] = { pressed: false, touched: false, value: 0 };
        pool.push(pad);
    }
    var slots = new Array(MAX_PADS);

    function axisValue(pad, i) {
        if (!pad.axes) return 0;
        var v = pad.axes[i];
        return (typeof v === 'number' && isFinite(v)) ? v : 0;
    }

    function buttonValue(pad, i) {
        if (!pad.buttons) return 0;
        var v = pad.buttons[i];
        if (typeof v === 'number') return v;
        return v && typeof v.value === 'number' ? v.value : 0;
    }

    function clamp01(v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    function unipolar(v) { return clamp01((v + 1) / 2); }       // full axis -> button
    function positive(v) { return v > 0 ? (v > 1 ? 1 : v) : 0; }
    function negative(v) { return v < 0 ? (v < -1 ? 1 : -v) : 0; }
    function axisUnit(v) { return v < -1 ? -1 : (v > 1 ? 1 : v); }

    function looksLikeDirectInput(pad) {
        return !!(pad.axes && pad.axes.length >= 8 && pad.buttons && pad.buttons.length >= 11);
    }

    function shouldNormalize(pad) {
        if (cfg.mapping === 'standard') return false;
        if (cfg.mapping === 'dinput') return looksLikeDirectInput(pad);
        return pad.mapping !== 'standard' && looksLikeDirectInput(pad);
    }

    function setButton(out, i, v) {
        if (!(typeof v === 'number' && isFinite(v))) v = 0;
        v = clamp01(v);
        var btn = out.buttons[i];
        btn.value = v;
        btn.pressed = v > 0.5;
        btn.touched = v > 0;
    }

    /* Raw DirectInput/hat pad -> W3C standard (Chromium's MapperXInputStyleGamepad). */
    function normalize(raw, out) {
        out.index = raw.index;
        out.id = raw.id;
        out.connected = raw.connected !== false;
        out.mapping = 'standard';
        out.timestamp = raw.timestamp;

        out.axes[0] = axisUnit(axisValue(raw, 0));
        out.axes[1] = axisUnit(axisValue(raw, 1));
        out.axes[2] = axisUnit(axisValue(raw, 3));
        out.axes[3] = axisUnit(axisValue(raw, 4));

        var v = [
            buttonValue(raw, 0), buttonValue(raw, 1), buttonValue(raw, 2), buttonValue(raw, 3),
            buttonValue(raw, 4), buttonValue(raw, 5),
            unipolar(axisValue(raw, 2)), unipolar(axisValue(raw, 5)),
            buttonValue(raw, 6), buttonValue(raw, 7), buttonValue(raw, 9), buttonValue(raw, 10),
            negative(axisValue(raw, 7)), positive(axisValue(raw, 7)),
            negative(axisValue(raw, 6)), positive(axisValue(raw, 6)),
            buttonValue(raw, 8)
        ];
        for (var i = 0; i < BUTTON_COUNT; i++) setButton(out, i, v[i]);
    }

    /* Already standard pad -> copy (so the caller never sees Chromium's live objects). */
    function copy(raw, out) {
        out.index = raw.index;
        out.id = raw.id;
        out.connected = raw.connected !== false;
        out.mapping = raw.mapping === undefined ? 'standard' : raw.mapping;
        out.timestamp = raw.timestamp;
        for (var i = 0; i < AXIS_COUNT; i++) out.axes[i] = axisUnit(axisValue(raw, i));
        for (var j = 0; j < BUTTON_COUNT; j++) setButton(out, j, buttonValue(raw, j));
    }

    /* ---------------------------------------------------------------- GameNative
     * shared-memory pad source.
     */
    var SHM_CANDIDATES = [
        // Z: is <app files>/imagefs, so Z:\.. is <app files> and Z:\..\gamepad_shm
        // is where GameNative (WinHandler) keeps the LIVE pad struct.
        'Z:\\..\\gamepad_shm\\gamepad.mem',
        'Z:\\..\\..\\gamepad_shm\\gamepad.mem',
        'Z:\\gamepad_shm\\gamepad.mem',
        'Z:\\data\\user\\0\\app.gamenative\\files\\gamepad_shm\\gamepad.mem',
        'Z:\\data\\data\\app.gamenative\\files\\gamepad_shm\\gamepad.mem',
        'C:\\data\\user\\0\\app.gamenative\\files\\gamepad_shm\\gamepad.mem',
        'C:\\data\\data\\app.gamenative\\files\\gamepad_shm\\gamepad.mem',
        'E:\\gamepad_shm\\gamepad.mem',
        'E:\\files\\gamepad_shm\\gamepad.mem',
        // Legacy location the launcher pre-creates (inside the rootfs); kept last
        // because it is a static 64-byte file that never receives pad state.
        'Z:\\tmp\\gamepad.mem'
    ];
    var SHM_SEARCH_ROOTS = ['C:\\', 'E:\\', 'Z:\\', 'Y:\\', 'D:\\'];
    var SHM_SEARCH_TARGET = 'gamepad.mem';
    var SHM_SEARCH_MAX_DIRS = 400;
    var SHM_SEARCH_MAX_DEPTH = 6;
    var shmFd = -1;
    var shmPath = null;
    var shmBuf = null;
    var shmLastPick = 0;
    var shmDeadReads = 0;
    var shmSkipSearch = false;
    var shmInfo = { path: null, error: null, candidates: {}, search: null, connected: null, raw: null };

    /* Bounded depth-first search for gamepad.mem (the file GameNative writes the
       live pad state into). Needed because Wine's drive letters do not map
       Android's data directory in a predictable place. */
    function searchShm(fsMod) {
        var visited = 0;
        var found = [];
        var rootStatus = {};
        var stack = [];
        for (var r = 0; r < SHM_SEARCH_ROOTS.length; r++) stack.push({ dir: SHM_SEARCH_ROOTS[r], depth: 0 });
        while (stack.length && visited < SHM_SEARCH_MAX_DIRS && found.length < 3) {
            var item = stack.pop();
            var entries;
            try { entries = fsMod.readdirSync(item.dir, { withFileTypes: true }); }
            catch (e) {
                if (item.depth === 0) rootStatus[item.dir] = (e && e.code) || String(e);
                continue;
            }
            visited++;
            for (var i = 0; i < entries.length; i++) {
                var ent = entries[i];
                var name = String(ent.name || '');
                if (name === SHM_SEARCH_TARGET) {
                    found.push((item.dir.replace(/[\\\/]$/, '')) + '\\' + name);
                }
                if (item.depth < SHM_SEARCH_MAX_DEPTH && (ent.isDirectory ? ent.isDirectory() : false) && name !== '.' && name !== '..') {
                    stack.push({ dir: (item.dir.replace(/[\\\/]$/, '')) + '\\' + name, depth: item.depth + 1 });
                }
            }
        }
        return { roots: rootStatus, dirs: visited, found: found };
    }

    function readAt(fsMod, file) {
        var fd;
        try { fd = fsMod.openSync(file, 'r'); } catch (e) { return null; }
        try {
            var buf = Buffer.alloc(64);
            fsMod.readSync(fd, buf, 0, 64, 0);
            return { fd: fd, buf: buf, bytes: buf };
        } catch (e) {
            try { fsMod.closeSync(fd); } catch (e2) { }
            return null;
        }
    }

    /* Pick the candidate that actually carries live pad state (connected flag set);
       fall back to the first readable one so the log still shows something. */
    function pickShm(fsMod) {
        var list = (cfg.shm && cfg.shm !== 'auto') ? [cfg.shm] : SHM_CANDIDATES.slice();
        if ((!cfg.shm || cfg.shm === 'auto') && !shmSkipSearch) {
            var res = searchShm(fsMod);
            shmInfo.search = res;
            for (var s = 0; s < res.found.length; s++)
                if (list.indexOf(res.found[s]) === -1) list.push(res.found[s]);
        }
        var first = null;
        for (var i = 0; i < list.length; i++) {
            var probe = readAt(fsMod, list[i]);
            if (!probe) { shmInfo.candidates[list[i]] = 'unreadable'; continue; }
            var connected = probe.buf.readInt32LE(40);
            shmInfo.candidates[list[i]] = 'ok connected=' + connected + ' seq=' + probe.buf.readUInt32LE(0);
            if (connected !== 0) { return probe.fd; }
            if (!first) first = probe.fd; else { try { fsMod.closeSync(probe.fd); } catch (e) { } }
        }
        return first;
    }

    function initShm(force) {
        if (cfg.shm === 'off') return;
        if (shmFd >= 0 && !force) return;
        var fsMod;
        try { fsMod = require('fs'); } catch (e) { return; }
        if (shmFd >= 0 && force) { try { fsMod.closeSync(shmFd); } catch (e) { } shmFd = -1; shmInfo.candidates = {}; }
        shmFd = pickShm(fsMod) || -1;
        shmPath = null;
        shmInfo.error = shmFd < 0 ? 'gamepad.mem not found' : null;
        if (shmFd >= 0) {
            var keys = Object.keys(shmInfo.candidates);
            for (var i = 0; i < keys.length; i++) {
                if (shmInfo.candidates[keys[i]].indexOf('ok ') === 0) { shmPath = keys[i]; break; }
            }
            shmInfo.path = shmPath;
            shmBuf = Buffer.alloc(64);
        }
    }

    function readShm(out) {
        initShm();
        if (shmFd < 0) return false;
        try {
            fs.readSync(shmFd, shmBuf, 0, 64, 0);
        } catch (e) {
            shmInfo.error = String(e && e.code || e);
            return false;
        }
        var connected = shmBuf.readInt32LE(40);
        if (connected === 0) {
            /* The picked file carries no live state. Another candidate may be the
               live one - re-probe every few seconds (cheap, no directory walk). */
            shmDeadReads++;
            var now = Date.now();
            if (shmDeadReads > 120 && now - shmLastPick > 5000) {
                shmLastPick = now;
                shmDeadReads = 0;
                shmSkipSearch = true;
                initShm(true);
                shmSkipSearch = false;
                if (shmFd >= 0) {
                    try { fs.readSync(shmFd, shmBuf, 0, 64, 0); } catch (e) { return false; }
                    connected = shmBuf.readInt32LE(40);
                }
            }
        } else {
            shmDeadReads = 0;
        }
        shmInfo.connected = connected;
        shmInfo.raw = {
            seq: shmBuf.readUInt32LE(0),
            lx: shmBuf.readInt16LE(4), ly: shmBuf.readInt16LE(6),
            rx: shmBuf.readInt16LE(8), ry: shmBuf.readInt16LE(10),
            lt: shmBuf.readInt16LE(12), rt: shmBuf.readInt16LE(14),
            btn: Array.prototype.slice.call(shmBuf, 16, 31),
            hat: shmBuf[31]
        };
        if (!connected) return false;

        var s = function (i) { return shmBuf[16 + i] ? 1 : 0; };
        out.index = 0;
        out.id = 'GameNative Pad (shared memory)';
        out.connected = true;
        out.mapping = 'standard';
        out.timestamp = Date.now();
        out.axes[0] = axisUnit(shmBuf.readInt16LE(4) / 32767);
        out.axes[1] = axisUnit(shmBuf.readInt16LE(6) / 32767);
        out.axes[2] = axisUnit(shmBuf.readInt16LE(8) / 32767);
        out.axes[3] = axisUnit(shmBuf.readInt16LE(10) / 32767);
        setButton(out, 0, s(0));
        setButton(out, 1, s(1));
        setButton(out, 2, s(2));
        setButton(out, 3, s(3));
        setButton(out, 4, s(9));                                    // LB
        setButton(out, 5, s(10));                                   // RB
        setButton(out, 6, unipolar(shmBuf.readInt16LE(12) / 32767)); // LT axis
        setButton(out, 7, unipolar(shmBuf.readInt16LE(14) / 32767)); // RT axis
        setButton(out, 8, s(4));                                    // Back
        setButton(out, 9, s(6));                                    // Start
        setButton(out, 10, s(7));                                   // L3
        setButton(out, 11, s(8));                                   // R3
        setButton(out, 12, s(11));                                  // D-pad up
        setButton(out, 13, s(12));                                  // D-pad down
        setButton(out, 14, s(13));                                  // D-pad left
        setButton(out, 15, s(14));                                  // D-pad right
        setButton(out, 16, s(5));                                   // Guide
        return true;
    }

    /* ------------------------------------------------------------ GameNative
     * UDP pad server source (WinHandler.java, port 7947).
     *
     * GameNative's own input DLLs query the host over UDP; Chromium never does,
     * so we speak that protocol from here. Requests and replies are
     * little-endian:
     *
     *   -> byte 8, byte isXInput, byte notify, i32 processId          (GET_GAMEPAD)
     *   <- byte 8, i32 gamepadId, byte dinputMapperType, i32 len, name
     *
     *   -> byte 9, i32 gamepadId                                      (GET_GAMEPAD_STATE)
     *   <- byte 9, byte enabled, [ i32 gamepadId, i16 buttons, u8 povHat,
     *                               i16 LX, i16 LY, i16 RX, i16 RY, u8 LT, u8 RT ]
     *
     * buttons is a bitfield: 0 A, 1 B, 2 X, 3 Y, 4 LB, 5 RB, 6 Back, 7 Start,
     *                        8 L3, 9 R3.   povHat: 0 up, 2 right, 4 down, 6 left.
     */
    var GN_PORT = 7947;
    var GN_HOST = '127.0.0.1';
    var GN_POLL_MS = 20;
    var GN_STALE_MS = 500;
    var gn = {
        sock: null, gamepadId: 0, name: '', enabled: false, state: null,
        lastOk: 0, lastRegister: 0, lastPoll: 0, requestFlavour: null, log: []
    };

    function gnNote(text) {
        if (gn.log.length < 12) gn.log.push(text);
    }

    function gnRegister(isXInput) {
        if (!gn.sock) return;
        var b = Buffer.alloc(7);
        b[0] = 8;
        b[1] = isXInput ? 1 : 0;
        b[2] = 0;
        var pid = (typeof process !== 'undefined' && process.pid) ? process.pid : 0;
        b.writeInt32LE(pid & 0x7fffffff, 3);
        gn.requestFlavour = isXInput ? 1 : 0;
        try { gn.sock.send(b, 0, b.length, GN_PORT, GN_HOST); } catch (e) { gnNote('reg send failed'); }
    }

    function gnOnMessage(msg) {
        if (!msg || msg.length < 2) return;
        if (msg[0] === 8) {
            if (msg.length < 10) return;
            var id = msg.readInt32LE(1);
            var nameLen = msg.readInt32LE(6);
            var name = (nameLen > 0 && msg.length >= 10 + nameLen)
                ? msg.slice(10, 10 + nameLen).toString('utf8').replace(/\0+$/, '')
                : '';
            gnNote('GET_GAMEPAD isXInput=' + gn.requestFlavour + ' id=' + id + ' name=' + name);
            if (id > 0) { gn.gamepadId = id; gn.name = name; gn.enabled = true; }
        } else if (msg[0] === 9) {
            gn.enabled = msg[1] === 1;
            if (!gn.enabled || msg.length < 19) { gn.state = null; return; }
            gn.state = {
                buttons: msg.readInt16LE(6),
                hat: msg[8] > 7 ? -1 : msg[8],
                lx: msg.readInt16LE(9), ly: msg.readInt16LE(11),
                rx: msg.readInt16LE(13), ry: msg.readInt16LE(15),
                lt: msg[17], rt: msg[18]
            };
            gn.lastOk = Date.now();
        }
    }

    var gnStart = 0;
    try { gnStart = Date.now(); } catch (e) { }

    function gnInit() {
        if (gn.sock || cfg.gnUdp === 'off' || gn.failed) return;
        /* Opening a socket early (or in some Wine renderers at all) can take the
           whole renderer down, so wait until the game has been up for a while. */
        if (Date.now() - gnStart < (cfg.gnUdpDelayMs || 0)) return;
        var dgram;
        try { dgram = require('dgram'); } catch (e) { gnNote('no dgram'); gn.failed = true; return; }
        var sock;
        try { sock = dgram.createSocket('udp4'); } catch (e) { gnNote('socket failed'); gn.failed = true; return; }
        sock.on('error', function () { });
        sock.on('message', gnOnMessage);
        try { sock.bind(); } catch (e) { gnNote('bind failed'); gn.failed = true; try { sock.close(); } catch (e2) { } return; }
        gn.sock = sock;
        gn.lastRegister = Date.now();
        gnRegister(0);
        gnRegister(1);
    }

    function gnPoll() {
        gnInit();
        if (!gn.sock) return;
        var now = Date.now();
        if (now - gn.lastPoll < GN_POLL_MS) return;
        gn.lastPoll = now;
        if (now - gn.lastRegister > 5000) {
            gn.lastRegister = now;
            gnRegister(gn.requestFlavour === 1 ? 1 : 0);
        }
        var b = Buffer.alloc(5);
        b[0] = 9;
        b.writeInt32LE(gn.gamepadId, 1);
        try { gn.sock.send(b, 0, b.length, GN_PORT, GN_HOST); } catch (e) { }
    }

    function gnPad(out) {
        if (cfg.gnUdp === 'off') return false;
        gnPoll();   /* the game calls getGamepads() every frame - no timer needed */
        if (!gn.state || Date.now() - gn.lastOk > GN_STALE_MS) return false;
        var st = gn.state;
        out.index = 0;
        out.id = 'GameNative Pad (UDP' + (gn.name ? ': ' + gn.name : '') + ')';
        out.connected = true;
        out.mapping = 'standard';
        out.timestamp = Date.now();
        out.axes[0] = axisUnit(st.lx / 32767);
        out.axes[1] = axisUnit(st.ly / 32767);
        out.axes[2] = axisUnit(st.rx / 32767);
        out.axes[3] = axisUnit(st.ry / 32767);
        var bit = function (i) { return (st.buttons & (1 << i)) ? 1 : 0; };
        setButton(out, 0, bit(0));                 // A
        setButton(out, 1, bit(1));                 // B
        setButton(out, 2, bit(2));                 // X
        setButton(out, 3, bit(3));                 // Y
        setButton(out, 4, bit(4));                 // LB
        setButton(out, 5, bit(5));                 // RB
        setButton(out, 6, st.lt / 255);            // LT
        setButton(out, 7, st.rt / 255);            // RT
        setButton(out, 8, bit(6));                 // Back
        setButton(out, 9, bit(7));                 // Start
        setButton(out, 10, bit(8));                // L3
        setButton(out, 11, bit(9));                // R3
        var hat = st.hat;
        setButton(out, 12, (hat === 7 || hat === 0 || hat === 1) ? 1 : 0);
        setButton(out, 13, (hat === 3 || hat === 4 || hat === 5) ? 1 : 0);
        setButton(out, 14, (hat === 5 || hat === 6 || hat === 7) ? 1 : 0);
        setButton(out, 15, (hat === 1 || hat === 2 || hat === 3) ? 1 : 0);
        setButton(out, 16, 0);
        return true;
    }

    function getGamepads() {
        var source = rawGetGamepads ? (rawGetGamepads() || []) : [];
        var used = 0, i, j;
        for (i = 0; i < source.length && used < MAX_PADS; i++) {
            var raw = source[i];
            if (!raw || !raw.connected) continue;
            if (cfg.padIndex !== null && raw.index !== cfg.padIndex) continue;
            var out = pool[used];
            if (shouldNormalize(raw)) normalize(raw, out);
            else copy(raw, out);
            slots[used] = out;
            used++;
        }
        if (used === 0 && readShm(pool[0])) {
            slots[0] = pool[0];
            used = 1;
        }
        if (used === 0 && gnPad(pool[0])) {
            slots[0] = pool[0];
            used = 1;
        }
        for (j = used; j < MAX_PADS; j++) slots[j] = null;
        if (cfg.debug) {
            var s = [];
            for (var d = 0; d < used; d++) {
                s.push(slots[d].id + ' A=' + slots[d].buttons[0].value +
                    ' RT=' + slots[d].buttons[7].value);
            }
            console.log('[gamepad-fix] ' + (s.length ? s.join(' | ') : 'no gamepads') +
                ' | shm=' + (shmInfo.path || 'none') + ' connected=' + shmInfo.connected);
        }
        return slots;
    }

    navigator.getGamepads = getGamepads;

        window.__gamepadFix = {
            raw: function () { return rawGetGamepads ? Array.prototype.slice.call(rawGetGamepads()) : []; },
            shm: function () { initShm(); readShm(pool[3]); return shmInfo; },
            gn: function () { gnInit(); return { id: gn.gamepadId, name: gn.name, enabled: gn.enabled, state: gn.state, ok: (Date.now() - gn.lastOk) < 2000, failed: !!gn.failed, log: gn.log }; },
            config: cfg
        };
    } catch (e) {
        /* Never let the fix break the game. */
        try { window.__gamepadFix = { error: String(e && e.message || e), config: cfg }; } catch (e2) { }
    }
})();

/* DIAGNOSTIC BUILD: appends to D:\alabaster-dawn-fix\gpf-log.txt (phone Downloads). */
(function () {
    'use strict';
    try {
        var fs = null;
        try { fs = require('fs'); } catch (e) { }
        var CANDIDATES = ['D:\\alabaster-dawn-fix\\gpf-log.txt', 'D:\\gpf-log.txt'];
        var LOGFILE = null, RUN = Date.now();
        function pick() {
            if (LOGFILE || !fs) return LOGFILE;
            for (var i = 0; i < CANDIDATES.length; i++) {
                try { fs.appendFileSync(CANDIDATES[i], ''); LOGFILE = CANDIDATES[i]; return LOGFILE; } catch (e) { }
            }
            return null;
        }
        function write(o) { if (!pick()) return; try { fs.appendFileSync(LOGFILE, JSON.stringify(o) + '\n'); } catch (e) { } }
        function num(v) { return Math.round(v * 1000) / 1000; }
        function dump(list) {
            var out = [];
            if (!list) return out;
            for (var i = 0; i < list.length; i++) {
                var p = list[i]; if (!p) continue;
                out.push({ i: p.index, id: p.id, m: p.mapping, c: p.connected,
                    ax: Array.prototype.slice.call(p.axes).map(num),
                    bt: Array.prototype.slice.call(p.buttons).map(function (b) { return num(typeof b === 'number' ? b : (b && b.value) || 0); }) });
            }
            return out;
        }
        write({ ev: 'load', run: RUN, t: Date.now(),
            nw: (process.versions && process.versions.nw) || null, node: (process.versions && process.versions.node) || null,
            arch: process.arch, stage: 'shim-loaded' });
        var ticks = 0, stage = 'first-frame';
        setInterval(function () {
            ticks++;
            try {
                if (ticks === 1) { stage = 'tick1'; write({ ev: 'stage', run: RUN, t: Date.now(), stage: stage }); }
                if (ticks > 300 && ticks % 30 !== 0) return;
                var raw = (window.__gamepadFix && window.__gamepadFix.raw) ? window.__gamepadFix.raw() : [];
                var pads = [];
                try { pads = navigator.getGamepads(); } catch (e) { }
                var gx = (window.__gamepadFix && window.__gamepadFix.gn) ? window.__gamepadFix.gn() : null;
                write({ ev: 'pads', run: RUN, t: Date.now(), tick: ticks, ev2: stage, focus: document.hasFocus(),
                    raw: dump(raw), shim: dump(pads),
                    gn: gx ? { id: gx.id, name: gx.name, enabled: gx.enabled, ok: gx.ok, failed: gx.failed, state: gx.state, log: gx.log } : null });
            } catch (e) {
                write({ ev: 'tickerror', run: RUN, t: Date.now(), tick: ticks, msg: String(e && e.message || e) });
            }
        }, 1000);
        setTimeout(function () { write({ ev: 'stage', run: RUN, t: Date.now(), stage: 'after-' + (cfgDelay()) + 'ms' }); }, 5200);
        function cfgDelay() { try { return (window.__gamepadFix && window.__gamepadFix.config && window.__gamepadFix.config.gnUdpDelayMs) || 0; } catch (e) { return 0; } }
    } catch (e) {
        try { require('fs').appendFileSync('D:\\alabaster-dawn-fix\\gpf-log.txt', JSON.stringify({ ev: 'diagerror', msg: String(e && e.message || e) }) + '\n'); } catch (e2) { }
    }
})();
