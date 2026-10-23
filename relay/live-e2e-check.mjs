// Live end-to-end check against the deployed relay.
// node relay/live-e2e-check.mjs
import WebSocket from "ws";

const BASE = "https://remote-control-relay.adhiphub-rc.workers.dev";
const WS = BASE.replace("https://", "wss://");
const DEVICE = "e2e-test-phone";
const TIMEOUT = 15000;

let failures = 0;
function check(name, ok, extra) {
  console.log((ok ? "PASS" : "FAIL") + "  " + name + (extra ? "  (" + extra + ")" : ""));
  if (!ok) failures++;
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

function once(ws, match, timeout = TIMEOUT) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error("timeout waiting for message")), timeout);
    const onMsg = (raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (match(msg)) { clearTimeout(t); ws.off("message", onMsg); resolve(msg); }
    };
    ws.on("message", onMsg);
  });
}

// 1. Device connects (what the Android app does).
const device = new WebSocket(WS + "/ws?sessionId=" + encodeURIComponent(DEVICE) + "&role=device&model=" + encodeURIComponent(DEVICE));
await new Promise((res, rej) => { device.once("open", res); device.once("error", rej); });
check("device socket connected", true);

// 2. Console list shows the device online.
await wait(1500);
let list = await (await fetch(BASE + "/console?mode=list")).json();
let entry = list.devices.find((d) => d.deviceId === DEVICE);
check("console lists device online", entry && entry.online, JSON.stringify(entry));

// 3. Browser connects (what the control page does).
const browser = new WebSocket(WS + "/ws?sessionId=");
await new Promise((res, rej) => { browser.once("open", res); browser.once("error", rej); });
check("browser socket connected", true);

// 4. Command to locked device must be rejected.
const ack = once(browser, (m) => m.type === "error");
browser.send(JSON.stringify({ action: "ping", deviceId: DEVICE, id: "t1" }));
let err = await ack;
check("locked device rejects commands", /lock/i.test(err.message || ""), err.message);

// 5. Unlock with the right password.
let unlock = await (await fetch(BASE + "/console/unlock?deviceId=" + encodeURIComponent(DEVICE) + "&pw=00100")).json();
check("unlock with 00100 succeeds", unlock.success === true, JSON.stringify(unlock));

// 6. Command now reaches the device; device answers; answer reaches browser.
const gotCommand = once(device, (m) => m.action === "ping");
browser.send(JSON.stringify({ action: "ping", deviceId: DEVICE, id: "t2" }));
const cmd = await gotCommand;
check("unlocked command reaches device", cmd.id === "t2", JSON.stringify(cmd));

const gotReply = once(browser, (m) => m.type === "pong" || m.action === "pong");
device.send(JSON.stringify({ type: "pong", action: "pong", id: "t2" }));
const reply = await gotReply;
check("device reply reaches browser", reply.id === "t2");

// 7. Wrong password rejected.
let bad = await fetch(BASE + "/console/unlock?deviceId=" + encodeURIComponent(DEVICE) + "&pw=99999");
check("wrong password rejected", bad.status === 403, "status " + bad.status);

// 8. Device disconnect -> console marks it offline.
device.close();
await wait(3000);
list = await (await fetch(BASE + "/console?mode=list")).json();
entry = list.devices.find((d) => d.deviceId === DEVICE);
check("device marked offline after disconnect", entry && entry.online === false, JSON.stringify(entry));

// 9. Forget removes it from the console.
let forget = await (await fetch(BASE + "/console/forget?deviceId=" + encodeURIComponent(DEVICE) + "&pw=00100")).json();
list = await (await fetch(BASE + "/console?mode=list")).json();
check("forget removes device from console", forget.success && !list.devices.some((d) => d.deviceId === DEVICE));

browser.close();
console.log(failures === 0 ? "\nALL CHECKS PASSED" : "\n" + failures + " CHECK(S) FAILED");
process.exit(failures === 0 ? 0 : 1);
