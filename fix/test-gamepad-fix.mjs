/* Smoke test for gamepad-fix.js.
 *
 *   node test-gamepad-fix.mjs
 *
 * Covers both mechanisms:
 *   - non-standard (raw DirectInput/hat) Chromium pads are re-decoded into the
 *     W3C standard layout - byte for byte what Chromium's own
 *     MapperXInputStyleGamepad produces for an XInput pad;
 *   - when Chromium reports no pads at all (GameNative/Wine), a pad is
 *     synthesized from GameNative's 64-byte shared-memory struct.
 */
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const source = fs.readFileSync(new URL('./gamepad-fix.js', import.meta.url), 'utf8');

function shmBytes({ connected = 1, lx = 0, ly = 0, rx = 0, ry = 0, lt = -32767, rt = -32767, buttons = {}, hat = 0xff } = {}) {
    const b = Buffer.alloc(64);
    b.writeUInt32LE(1, 0);
    b.writeInt16LE(lx, 4); b.writeInt16LE(ly, 6); b.writeInt16LE(rx, 8); b.writeInt16LE(ry, 10);
    b.writeInt16LE(lt, 12); b.writeInt16LE(rt, 14);
    for (const [i, v] of Object.entries(buttons)) b[16 + Number(i)] = v ? 1 : 0;
    b[31] = hat;
    b.writeInt32LE(connected, 40);
    return b;
}

function load(rawPads, memBuf = null, config = null, tree = null, udp = null) {
    const isMem = (p) => memBuf && String(p).endsWith('gamepad.mem') && (!tree || tree.has(String(p)));
    const fsStub = {
        existsSync: (p) => config !== null && String(p).endsWith('gamepad-fix.json'),
        readFileSync: () => JSON.stringify(config),
        statSync: (p) => { if (isMem(p)) return { size: 64 }; const e = new Error('ENOENT'); e.code = 'ENOENT'; throw e; },
        openSync: (p) => { if (isMem(p)) return 7; const e = new Error('ENOENT'); e.code = 'ENOENT'; throw e; },
        readSync: (fd, buf) => { memBuf.copy(buf, 0, 0, 64); return 64; },
        readdirSync: (dir) => {
            const d = String(dir).replace(/[\\/]$/, '') + '\\';
            const out = [];
            if (tree) {
                for (const full of tree) {
                    if (!full.startsWith(d)) continue;
                    const rest = full.slice(d.length);
                    if (rest.includes('\\')) continue;
                    out.push({ name: rest, isDirectory: () => full.endsWith('\\') });
                }
            }
            return out;
        }
    };
    const sandbox = {
        navigator: { getGamepads: () => rawPads },
        window: {}, console, Buffer, Date,
        process: { pid: 4242 },
        setInterval: () => 0,
        require: (name) => {
            if (name === 'fs') return fsStub;
            if (name === 'path') return { join: (...parts) => parts.join('/') };
            if (name === 'dgram') {
                return {
                    createSocket: () => {
                        const handlers = {};
                        const sock = {
                            sent: [],
                            on: (ev, cb) => { handlers[ev] = cb; },
                            bind: () => { },
                            send: (buf) => { sock.sent.push(Buffer.from(buf)); },
                            deliver: (buf) => { if (handlers.message) handlers.message(Buffer.from(buf)); }
                        };
                        if (udp) udp.socket = sock;
                        return sock;
                    }
                };
            }
            throw new Error('unexpected require: ' + name);
        },
        __dirname: '/game/terra'
    };
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox);
    return { get: sandbox.navigator.getGamepads, window: sandbox.window };
}

const btn = (pad, i) => pad.buttons[i].value;
const axes = (pad) => Array.from(pad.axes).map((v) => Math.round(v * 1000) / 1000);

// --- 1. raw DirectInput/hat pad (mapping == "") is re-decoded ------------------
{
    const rawPad = {
        index: 0, id: 'Generic USB Joystick', connected: true, mapping: '', timestamp: 1,
        // X, Y, Z(LT), RX, RY, RZ(RT), HAT0X, HAT0Y
        axes: [0.5, -0.25, 0.5, -0.5, 0.25, -0.5, 1, -1],
        // A, B, X, Y, LB, RB, Back, Start, Guide, L3, R3
        buttons: [1, 0, 0, 0, 0.5, 0, 1, 1, 0, 0, 0].map((value) => ({ value, pressed: value > 0.5 }))
    };
    const { get } = load([rawPad]);
    const pads = get();
    assert.equal(pads.length, 4, 'fixed-length 4-slot array');
    const p = pads[0];
    assert.equal(p.mapping, 'standard', 'normalized pads claim the standard mapping');
    assert.equal(p.id, rawPad.id, 'id is preserved');
    assert.deepEqual(axes(p), [0.5, -0.25, -0.5, 0.25], 'left/right sticks');
    assert.equal(btn(p, 0), 1, 'A');
    assert.equal(btn(p, 4), 0.5, 'LB');
    assert.equal(btn(p, 6), 0.75, 'LT from axis Z (-1..1 -> 0..1)');
    assert.equal(btn(p, 7), 0.25, 'RT from axis RZ');
    assert.equal(btn(p, 8), 1, 'Back');
    assert.equal(btn(p, 9), 1, 'Start');
    assert.equal(btn(p, 12), 1, 'D-pad up from negative HAT0Y');
    assert.equal(btn(p, 13), 0, 'D-pad down');
    assert.equal(btn(p, 14), 0, 'D-pad left');
    assert.equal(btn(p, 15), 1, 'D-pad right from positive HAT0X');
    assert.equal(pads[1], null, 'unused slots are null');
    assert.equal(get()[0], p, 'pad objects are reused, not reallocated per frame');
}

// --- 2. standard pads pass through, shm is not consulted ----------------------
{
    const stdPad = {
        index: 3, id: '8BitDo Ultimate Wireless Controller (STANDARD GAMEPAD Vendor: 2dc8 Product: 3106)',
        connected: true, mapping: 'standard', timestamp: 2,
        axes: [0.25, 0, 0, 0], buttons: [{ value: 0.25, pressed: false }]
    };
    const { get } = load([stdPad], shmBytes({ connected: 1, buttons: { 0: 1 } }));
    const p = get()[0];
    assert.equal(p.id, stdPad.id);
    assert.equal(p.axes[0], 0.25);
    assert.equal(p.buttons[0].value, 0.25);
}

// --- 3. no Chromium pads -> pad synthesized from GameNative shared memory -----
{
    const mem = shmBytes({ connected: 1, lx: -32767, ly: 16384, rx: 32767, lt: 32767, buttons: { 0: 1, 9: 1, 10: 1, 12: 1 } });
    const { get } = load([], mem);
    const pad = get()[0];
    assert.equal(pad.mapping, 'standard');
    assert.equal(pad.id, 'GameNative Pad (shared memory)');
    assert.deepEqual(axes(pad), [-1, 0.5, 1, 0], 'sticks from the shared struct');
    assert.equal(btn(pad, 0), 1, 'A (SDL button 0)');
    assert.equal(btn(pad, 4), 1, 'LB (SDL button 9)');
    assert.equal(btn(pad, 5), 1, 'RB (SDL button 10)');
    assert.equal(btn(pad, 6), 1, 'LT pressed (trigger axis)');
    assert.equal(btn(pad, 7), 0, 'RT released');
    assert.equal(btn(pad, 13), 1, 'D-pad down (SDL button 12)');
    assert.equal(get()[1], null);
}

// --- 4. shared memory says "not connected" -> no pad --------------------------
{
    assert.equal(load([], shmBytes({ connected: 0 })).get()[0], null);
}

// --- 5. neither source available -> empty ------------------------------------
{
    assert.equal(load([], null).get()[0], null);
}

// --- 6. shm: "off" disables the shared-memory source -------------------------
{
    assert.equal(load([], shmBytes({ connected: 1, buttons: { 0: 1 } }), { shm: 'off' }).get()[0], null);
}

// --- 7. config can pin a pad index and force pass-through --------------------
{
    const a = { index: 0, id: 'first', connected: true, mapping: '', axes: [0, 0, 0, 0, 0, 0, 0, 0], buttons: [] };
    const b = { index: 1, id: 'second', connected: true, mapping: '', axes: [0, 0, 0, 0, 0, 0, 0, 0], buttons: [] };
    const pads = load([a, b], null, { mapping: 'standard', padIndex: 1 }).get();
    assert.equal(pads[0].id, 'second', 'padIndex selects the pad');
    assert.equal(pads[0].mapping, '', 'mapping "standard" leaves the pad untouched');
    assert.equal(pads[1], null);
}


// --- 8. shm file found by the filesystem search when no candidate path hits ----
{
    const memPath = 'C:\\data\\data\\app.gamenative\\files\\gamepad_shm\\gamepad.mem';
    const tree = new Set([
        'C:\\data\\', 'C:\\data\\data\\', 'C:\\data\\data\\app.gamenative\\',
        'C:\\data\\data\\app.gamenative\\files\\', 'C:\\data\\data\\app.gamenative\\files\\gamepad_shm\\',
        memPath
    ]);
    const { get, window } = load([], shmBytes({ connected: 1, buttons: { 0: 1 } }), null, tree);
    const pad = get()[0];
    assert.equal(pad && pad.buttons[0].value, 1, 'pad synthesized from a searched-out shm file');
    assert.equal(window.__gamepadFix.shm().path, memPath, 'search reports the file it found');
}


// --- 9. pad fetched from GameNative's UDP pad server -------------------------
{
    const udp = {};
    const { get } = load([], null, { shm: 'off', gnUdpDelayMs: 0 }, null, udp);
    get();                                     // triggers socket creation + registration
    assert.ok(udp.socket, 'udp socket was created');
    assert.equal(udp.socket.sent[0][0], 8, 'GET_GAMEPAD request sent (code 8)');
    assert.equal(udp.socket.sent[0].length, 7, 'GET_GAMEPAD request is 7 bytes');

    // server reply: id=42, mapper=1, name="Xbox 360 Controller"
    const name = Buffer.from('Xbox 360 Controller', 'utf8');
    const reg = Buffer.alloc(10 + name.length);
    reg[0] = 8; reg.writeInt32LE(42, 1); reg[5] = 1; reg.writeInt32LE(name.length, 6); name.copy(reg, 10);
    udp.socket.deliver(reg);

    // state reply: A + LB pressed, LX = -32767, hat = down, LT = 255
    const st = Buffer.alloc(19);
    st[0] = 9; st[1] = 1; st.writeInt32LE(42, 2);
    st.writeInt16LE((1 << 0) | (1 << 4), 6);   // A, LB
    st[8] = 4;                                 // pov hat: down
    st.writeInt16LE(-32767, 9);
    st.writeInt16LE(0, 11); st.writeInt16LE(0, 13); st.writeInt16LE(0, 15);
    st[17] = 255; st[18] = 0;
    udp.socket.deliver(st);

    const pad = get()[0];
    assert.ok(pad, 'pad synthesized from the UDP state');
    assert.equal(pad.mapping, 'standard');
    assert.match(pad.id, /GameNative Pad \(UDP/);
    assert.deepEqual(Array.from(pad.axes).map((v) => Math.round(v * 1000) / 1000), [-1, 0, 0, 0]);
    assert.equal(pad.buttons[0].value, 1, 'A');
    assert.equal(pad.buttons[4].value, 1, 'LB');
    assert.equal(pad.buttons[6].value, 1, 'LT from trigger byte');
    assert.equal(pad.buttons[13].value, 1, 'D-pad down from the POV hat');
    assert.equal(pad.buttons[0].pressed, true);

    // a 9-state poll must have been sent for the registered pad id
    const poll = udp.socket.sent.filter((b) => b[0] === 9);
    assert.ok(poll.length >= 1, 'GET_GAMEPAD_STATE poll sent');

    // disabled reply -> pad disappears
    const off = Buffer.alloc(3); off[0] = 9; off[1] = 0;
    udp.socket.deliver(off);
    assert.equal(get()[0], null, 'no pad while the server reports disabled');
}

console.log('gamepad-fix.js: all checks passed');
