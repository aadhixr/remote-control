/**
 * WebSocket relay for the Remote Control Android app.
 *
 * Architecture:
 *   - One SessionDO per session id (the device's Build.MODEL) holds the live
 *     device socket and any browser sockets for that session.
 *   - One RegistryDO (well-known name) tracks EVERY connected device across
 *     all sessions: model, online, unlocked. The control page reads it via
 *     /console?mode=list and unlocks devices with password 00100.
 *   - Browser commands are routed cross-DO to the session DO that owns the
 *     target device, so the console can control any device without knowing
 *     its session up front.
 *
 * URL shapes:
 *   /ws?sessionId=<id>                  browser (or device without header)
 *   /ws?sessionId=<id>&role=device      device (app sends X-Session-Id too)
 *   /console?mode=list                  all live devices (registry)
 *   /console/unlock?deviceId=..&pw=00100  unlock a device (registry)
 *   /console/forget?deviceId=..&pw=00100  forget a device (registry)
 *   /healthz                            health check
 */

const ROLE_TAG_DEVICE = "device";
const ROLE_TAG_BROWSER = "browser";
const UNLOCK_PASSWORD = "00100";
const REGISTRY_NAME = "__device_registry__";
// All console browsers share one well-known session so device replies can be
// delivered to them regardless of which session the phone belongs to.
const CONSOLE_ROOM = "__console__";

function safeSend(ws, data) {
  try {
    ws.send(data);
  } catch (e) {
    /* socket already closing */
  }
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function attachmentOf(ws) {
  try {
    return ws.deserializeAttachment() || {};
  } catch (e) {
    return {};
  }
}

function roleOf(ws) {
  if (ws.tags && ws.tags.includes(ROLE_TAG_DEVICE)) return ROLE_TAG_DEVICE;
  if (ws.tags && ws.tags.includes(ROLE_TAG_BROWSER)) return ROLE_TAG_BROWSER;
  return attachmentOf(ws).role || ROLE_TAG_BROWSER;
}

/* ------------------------------------------------------------------ *
 * SessionDO — one instance per session id (= device model name).     *
 * Holds the device socket + that session's browser sockets.          *
 * ------------------------------------------------------------------ */
export class SessionDO {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.unlocked = false;
  }

  async notifyRegistry(message) {
    if (!this.env || !this.env.REGISTRY) return;
    try {
      const id = this.env.REGISTRY.idFromName(REGISTRY_NAME);
      await this.env.REGISTRY.get(id).fetch("https://relay/registry", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(message),
      });
    } catch (e) {
      /* registry unreachable — device still works within its session */
    }
  }

  async fetch(request) {
    const url = new URL(request.url);

    // Internal endpoint: the console room receives device replies here.
    if (url.pathname === "/__push") {
      const message = await request.text();
      for (const b of this.state.getWebSockets(ROLE_TAG_BROWSER)) {
        safeSend(b, message);
      }
      return json({ ok: true });
    }

    // Internal endpoint: console DO forwards a command for this session's device.
    if (url.pathname === "/__forward") {
      const message = await request.text();
      const devices = this.state.getWebSockets(ROLE_TAG_DEVICE);
      if (devices.length === 0) {
        return json({ error: "no device connected for this session" }, 404);
      }
      if (!this.unlocked) {
        return json({ error: "device is locked — unlock with password 00100" }, 403);
      }
      for (const d of devices) safeSend(d, message);
      return json({ ok: true });
    }

    // Internal endpoint: registry tells us this device was unlocked.
    if (url.pathname === "/__set_unlocked") {
      this.unlocked = true;
      return json({ ok: true });
    }

    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Expected a WebSocket upgrade\n", { status: 426 });
    }

    const role =
      url.searchParams.get("role") === "device" ||
      request.headers.get("X-Role") === "device"
        ? ROLE_TAG_DEVICE
        : ROLE_TAG_BROWSER;
    const session =
      url.searchParams.get("sessionId") ||
      request.headers.get("X-Session-Id") ||
      "default";
    const model = url.searchParams.get("model") || session;

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    if (role === ROLE_TAG_DEVICE) {
      // A reconnecting device replaces the stale socket for this session.
      for (const old of this.state.getWebSockets(ROLE_TAG_DEVICE)) {
        try {
          old.close(4000, "replaced by a new device connection");
        } catch (e) {
          /* ignore */
        }
      }
    }

    server.serializeAttachment({ role, session });
    await this.state.acceptWebSocket(server, [role]);

    const online = this.state.getWebSockets(ROLE_TAG_DEVICE).length > 0;

    if (role === ROLE_TAG_DEVICE) {
      // Register in the global device registry so the console can list it.
      await this.notifyRegistry({ op: "attach", deviceId: session, model });
      const attached = JSON.stringify({
        type: "relay",
        event: "device-attached",
        deviceId: session,
        model,
      });
      for (const b of this.state.getWebSockets(ROLE_TAG_BROWSER)) {
        safeSend(b, attached);
      }
      this.pushToConsoleRoom(attached);
      safeSend(
        server,
        JSON.stringify({ type: "relay", event: "device-attached", self: true })
      );
    } else {
      safeSend(
        server,
        JSON.stringify({ type: "relay", event: "joined", deviceOnline: online })
      );
      for (const d of this.state.getWebSockets(ROLE_TAG_DEVICE)) {
        safeSend(d, JSON.stringify({ type: "relay", event: "browser-joined" }));
      }
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    const att = attachmentOf(ws);

    if (roleOf(ws) === ROLE_TAG_DEVICE) {
      // Device -> browsers of this session, and to the shared console room
      // so the remote control page receives replies for any device.
      for (const b of this.state.getWebSockets(ROLE_TAG_BROWSER)) {
        safeSend(b, message);
      }
      this.pushToConsoleRoom(message);
      return;
    }

    // Browser -> device. Commands carry { deviceId }; route to the right DO.
    let parsed = null;
    try {
      parsed = JSON.parse(message);
    } catch (e) {
      /* non-JSON: forward within session */
    }
    const target = parsed && parsed.deviceId;

    if (!target || target === att.session) {
      const devices = this.state.getWebSockets(ROLE_TAG_DEVICE);
      if (devices.length === 0) {
        safeSend(
          ws,
          JSON.stringify({ type: "error", message: "no device connected for this session" })
        );
        return;
      }
      for (const d of devices) safeSend(d, message);
      return;
    }

    // Cross-session: hand the command to the DO that owns the target device.
    if (!this.env || !this.env.SESSIONS) {
      safeSend(ws, JSON.stringify({ type: "error", message: "routing unavailable" }));
      return;
    }
    try {
      const id = this.env.SESSIONS.idFromName(target);
      const resp = await this.env.SESSIONS.get(id).fetch("https://relay/__forward", {
        method: "POST",
        body: message,
      });
      if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        safeSend(
          ws,
          JSON.stringify({ type: "error", message: err.error || "delivery failed", deviceId: target })
        );
      }
    } catch (e) {
      safeSend(ws, JSON.stringify({ type: "error", message: "delivery failed" }));
    }
  }

  async pushToConsoleRoom(message) {
    if (!this.env || !this.env.SESSIONS) return;
    try {
      const id = this.env.SESSIONS.idFromName(CONSOLE_ROOM);
      await this.env.SESSIONS.get(id).fetch("https://relay/__push", {
        method: "POST",
        body: message,
      });
    } catch (e) {
      /* nobody in the console room — fine */
    }
  }

  async webSocketClose(ws) {
    await this.onLeave(ws);
  }

  async webSocketError(ws) {
    await this.onLeave(ws);
  }

  async onLeave(ws) {
    const att = attachmentOf(ws);
    if (roleOf(ws) === ROLE_TAG_DEVICE) {
      this.unlocked = false;
      await this.notifyRegistry({ op: "detach", deviceId: att.session });
      const payload = JSON.stringify({
        type: "relay",
        event: "device-detached",
        deviceId: att.session,
      });
      for (const peer of this.state.getWebSockets(ROLE_TAG_BROWSER)) {
        safeSend(peer, payload);
      }
      this.pushToConsoleRoom(payload);
    } else {
      for (const peer of this.state.getWebSockets()) {
        if (peer !== ws) {
          safeSend(peer, JSON.stringify({ type: "relay", event: "peer-left" }));
        }
      }
    }
  }
}

/* ------------------------------------------------------------------ *
 * RegistryDO — global list of every connected device + unlock state. *
 * State survives hibernation via DO storage (SQLite-backed).         *
 * ------------------------------------------------------------------ */
export class RegistryDO {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async loadDevices() {
    return (await this.state.storage.get("devices")) || {};
  }

  async saveDevices(devices) {
    await this.state.storage.put("devices", devices);
  }

  async fetch(request) {
    const url = new URL(request.url);

    // Session DOs report attach/detach here.
    if (request.method === "POST" && url.pathname === "/registry") {
      const msg = await request.json().catch(() => null);
      if (!msg || !msg.op) return json({ error: "bad message" }, 400);
      const devices = await this.loadDevices();
      if (msg.op === "attach") {
        devices[msg.deviceId] = {
          model: msg.model || msg.deviceId,
          online: true,
          unlocked: devices[msg.deviceId]?.unlocked || false,
          lastSeen: Date.now(),
        };
      } else if (msg.op === "detach") {
        if (devices[msg.deviceId]) {
          devices[msg.deviceId].online = false;
          devices[msg.deviceId].unlocked = false;
        }
      }
      await this.saveDevices(devices);
      return json({ ok: true });
    }

    // Control page: list every known device.
    if (url.pathname === "/list") {
      const devices = await this.loadDevices();
      return json({
        devices: Object.entries(devices).map(([deviceId, d]) => ({
          deviceId,
          model: d.model,
          online: !!d.online,
          unlocked: !!d.unlocked,
        })),
      });
    }

    // Control page: unlock a device with password 00100.
    if (url.pathname === "/unlock") {
      const deviceId = url.searchParams.get("deviceId");
      const pw = url.searchParams.get("pw");
      if (pw !== UNLOCK_PASSWORD) return json({ error: "invalid password" }, 403);
      const devices = await this.loadDevices();
      if (!devices[deviceId]) return json({ error: "unknown device" }, 404);
      devices[deviceId].unlocked = true;
      await this.saveDevices(devices);
      // Tell the device's session DO to accept commands.
      try {
        const id = this.env.SESSIONS.idFromName(deviceId);
        await this.env.SESSIONS.get(id).fetch("https://relay/__set_unlocked", {
          method: "POST",
        });
      } catch (e) {
        /* session DO will also unlock when the next command checks registry */
      }
      return json({ success: true, deviceId });
    }

    // Control page: forget a device (offline leftovers).
    if (url.pathname === "/forget") {
      const deviceId = url.searchParams.get("deviceId");
      const pw = url.searchParams.get("pw");
      if (pw !== UNLOCK_PASSWORD) return json({ error: "invalid password" }, 403);
      const devices = await this.loadDevices();
      delete devices[deviceId];
      await this.saveDevices(devices);
      return json({ success: true, deviceId });
    }

    return json({ error: "unknown console endpoint" }, 400);
  }
}

/* ------------------------------------------------------------------ *
 * Router                                                             *
 * ------------------------------------------------------------------ */
export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/healthz") {
      return new Response("ok\n", { headers: { "content-type": "text/plain" } });
    }

    // Console API -> registry DO.
    if (url.pathname === "/console" || url.pathname.startsWith("/console/")) {
      const target =
        url.pathname === "/console/unlock"
          ? "/unlock"
          : url.pathname === "/console/forget"
            ? "/forget"
            : "/list";
      const id = env.REGISTRY.idFromName(REGISTRY_NAME);
      const rewritten = new Request("https://relay" + target + url.search, {
        method: request.method,
        headers: request.headers,
      });
      return env.REGISTRY.get(id).fetch(rewritten);
    }

    if (url.pathname === "/ws") {
      // Optional shared secret: set with `wrangler secret put AUTH_TOKEN`;
      // clients then pass ?token=... (browser) or X-Relay-Token (device).
      if (env.AUTH_TOKEN) {
        const provided =
          url.searchParams.get("token") || request.headers.get("X-Relay-Token");
        if (provided !== env.AUTH_TOKEN) {
          return new Response("unauthorized\n", { status: 401 });
        }
      }

      // Empty/missing sessionId = a console browser -> shared console room.
      const sessionId =
        url.searchParams.get("sessionId") ||
        request.headers.get("X-Session-Id") ||
        CONSOLE_ROOM;

      // One Durable Object instance per session id.
      const id = env.SESSIONS.idFromName(sessionId);
      return env.SESSIONS.get(id).fetch(request);
    }

    // Everything else: the control page (static assets).
    return env.ASSETS.fetch(request);
  },
};
