/* Drop-in probe: prints what the page sees (raw vs. shimmed gamepads) to a JSONL file.
 * Usage: add <script src="dist/inpage-probe.js"></script> to terra/index.html (after the
 * fix, before/after bundle.js - the game reads navigator.getGamepads every frame).
 * Desktop: reads/writes /tmp/gp_out.json. In a Wine container: change the path (e.g. D:\...).
 */
(function () {
    var fs = require('fs');
    function pads(list) {
        var out = [];
        for (var i = 0; i < list.length; i++) {
            var p = list[i]; if (!p) continue;
            out.push({ i: p.index, id: p.id, m: p.mapping, c: p.connected,
                ax: Array.prototype.slice.call(p.axes).map(function (v) { return Math.round(v * 1000) / 1000; }),
                bt: Array.prototype.slice.call(p.buttons).map(function (b) { return b.value; }) });
        }
        return out;
    }
    setInterval(function () {
        try {
            fs.appendFileSync('/tmp/gp_out.json', JSON.stringify({
                t: Date.now(),
                focus: document.hasFocus(),
                vis: document.visibilityState,
                raw: pads((window.__gamepadFix && window.__gamepadFix.raw) ? window.__gamepadFix.raw() : navigator.getGamepads()),
                shim: pads(navigator.getGamepads())
            }) + '\n');
        } catch (e) { }
    }, 1000);
})();
