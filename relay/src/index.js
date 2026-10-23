/**
 * WebSocket relay for the Remote Control Android app.
 *
 * URL shape:  /ws?sessionId=<id>       (browser, or device without header)
 *             header X-Session-Id: <id> (device — what the app sends)
 *             header X-Role: device|browser is optional; ?role= also works.
 *
 * Forwarding rules:
 *   device -> all browsers of the same session
 *   browser -> the session's device
 *   joins/leaves are announced as {"type":"relay","event":...} control frames.
 *
 * Sockets are accepted through the hibernation API (state.acceptWebSocket),
 * so idle sessions cost nothing and survive restarts; the role travels both
 * as a tag (for getWebSockets lookups after a wake) and as an attachment.
 */

const ROLE_TAG_DEVICE = "device";
const ROLE_TAG_BROWSER = "browser";

function safeSend(ws, data) {
  try {
    ws.send(data);
  } catch (e) {
    /* socket already closing */
  }
}

function roleOf(ws) {
  if (ws.tags && ws.tags.includes(ROLE_TAG_DEVICE)) return ROLE_TAG_DEVICE;
  if (ws.tags && ws.tags.includes(ROLE_TAG_BROWSER)) return ROLE_TAG_BROWSER;
  try {
    const att = ws.deserializeAttachment();
    if (att && att.role) return att.role;
  } catch (e) {
    /* ignore */
  }
  return ROLE_TAG_BROWSER;
}

export class SessionDO {
  constructor(state) {
    this.state = state;
  }

  async fetch(request) {
    const url = new URL(request.url);

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
      for (const b of this.state.getWebSockets(ROLE_TAG_BROWSER)) {
        safeSend(b, JSON.stringify({ type: "relay", event: "device-attached" }));
      }
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
    const role = roleOf(ws);
    if (role === ROLE_TAG_DEVICE) {
      for (const b of this.state.getWebSockets(ROLE_TAG_BROWSER)) {
        safeSend(b, message);
      }
    } else {
      const devices = this.state.getWebSockets(ROLE_TAG_DEVICE);
      if (devices.length === 0) {
        safeSend(
          ws,
          JSON.stringify({
            type: "error",
            message: "no device connected for this session",
          })
        );
        return;
      }
      for (const d of devices) {
        safeSend(d, message);
      }
    }
  }

  async webSocketClose(ws) {
    await this.onLeave(ws);
  }

  async webSocketError(ws) {
    await this.onLeave(ws);
  }

  async onLeave(ws) {
    const role = roleOf(ws);
    const payload = JSON.stringify({
      type: "relay",
      event: role === ROLE_TAG_DEVICE ? "device-detached" : "peer-left",
    });
    for (const peer of this.state.getWebSockets()) {
      if (peer !== ws) safeSend(peer, payload);
    }
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/healthz") {
      return new Response("ok\n", { headers: { "content-type": "text/plain" } });
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

      const sessionId =
        url.searchParams.get("sessionId") ||
        request.headers.get("X-Session-Id") ||
        "default";

      // One Durable Object instance per session id.
      const id = env.SESSIONS.idFromName(sessionId);
      return env.SESSIONS.get(id).fetch(request);
    }

    // Everything else: the control page (static assets).
    return env.ASSETS.fetch(request);
  },
};
