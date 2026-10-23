/**
 * End-to-end relay check against the deployed Worker.
 *   node e2e-check.mjs [base-url]
 * Verifies:
 *   1. a browser on an empty session gets the "no device connected" error
 *   2. browser -> device forwarding
 *   3. device -> browser forwarding
 *   4. the relay announces the device attaching to waiting browsers
 * Exits non-zero on any failure.
 *
 * (Node's built-in WebSocket cannot set custom headers, so the device side
 * uses ?role=device — the Worker accepts session id from either the header
 * the Android app sends or the query string.)
 */
const base =
  process.argv[2] || "https://remote-control-relay.adhiphub-rc.workers.dev/ws";
const session = "e2e-" + Date.now();
const wsBase = base.replace(/^http/, "ws") + "?sessionId=" + session;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const open = (url) =>
  new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    ws.onopen = () => resolve(ws);
    ws.onerror = () => reject(new Error("connect failed: " + url));
  });

const browserMsgs = [];
const deviceMsgs = [];
let failures = 0;
const check = (name, ok) => {
  console.log((ok ? "PASS" : "FAIL") + "  " + name);
  if (!ok) failures++;
};

// 1. browser connects first, to an empty session
const browser = await open(wsBase + "&role=browser");
browser.onmessage = (e) => browserMsgs.push(String(e.data));
browser.send(JSON.stringify({ action: "ping", id: "early" }));
await sleep(900);
check(
  "browser-only session gets 'no device' error",
  browserMsgs.some((m) => m.includes("no device connected"))
);

// 2. device joins the same session
const device = await open(wsBase + "&role=device");
device.onmessage = (e) => deviceMsgs.push(String(e.data));
await sleep(500);

// 3. browser -> device
browser.send(JSON.stringify({ action: "click", x: 10, y: 20, id: "b2d" }));
await sleep(900);
check(
  "browser -> device forwarding",
  deviceMsgs.some((m) => m.includes('"action":"click"') && m.includes("b2d"))
);

// 4. device -> browser
device.send(
  JSON.stringify({ type: "result", action: "click", ok: true, id: "b2d" })
);
await sleep(900);
check(
  "device -> browser forwarding",
  browserMsgs.some((m) => m.includes('"type":"result"') && m.includes("b2d"))
);

// 5. attach announcement
check(
  "device-attach announced to browser",
  browserMsgs.some((m) => m.includes("device-attached"))
);

browser.close();
device.close();
await sleep(300);
process.exit(failures ? 1 : 0);
