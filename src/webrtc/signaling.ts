export type SignalType =
  | "JOIN"
  | "PEER_JOINED"
  | "PEER_LEFT"
  | "OFFER"
  | "ANSWER"
  | "ICE_CANDIDATE"
  | "LEAVE";

/** Mirrors de.cfe.gamecollection.backend.webrtc.SignalMessage */
export type SignalMessage = {
  type: SignalType;
  roomId: string;
  senderId: string;
  targetId?: string | null;
  sdp?: string | null;
  candidate?: string | null;
  sdpMid?: string | null;
  sdpMLineIndex?: number | null;
  peers?: string[] | null;
};

export type RawIceServer = {
  urls: string;
  username?: string | null;
  credential?: string | null;
};

const DEFAULT_SIGNALING_PORT = "8721";

/**
 * Turns whatever the user typed on the start screen ("192.168.1.5", "localhost:8721",
 * "https://signal.example.com") into the HTTP and WebSocket base URLs of the signaling server.
 */
export function parseServerAddress(input: string): { httpBase: string; wsBase: string } {
  const trimmed = input.trim().replace(/\/+$/, "");
  if (!trimmed) throw new Error("Bitte eine Adresse für den Signaling-Server angeben.");

  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;

  let url: URL;
  try {
    url = new URL(withScheme);
  } catch {
    throw new Error(`"${input}" ist keine gültige Serveradresse.`);
  }

  const secure = url.protocol === "https:" || url.protocol === "wss:";
  if (!url.port) url.port = DEFAULT_SIGNALING_PORT;

  return {
    httpBase: `${secure ? "https" : "http"}://${url.host}`,
    wsBase: `${secure ? "wss" : "ws"}://${url.host}`,
  };
}

export function signalingUrl(wsBase: string, roomId: string, peerId: string): string {
  const room = encodeURIComponent(roomId);
  const peer = encodeURIComponent(peerId);
  return `${wsBase}/ws/signaling?roomId=${room}&peerId=${peer}`;
}

/** Drops entries the browser would reject (empty urls, half-filled TURN credentials). */
export function toRtcIceServers(raw: RawIceServer[]): RTCIceServer[] {
  return raw
    .filter((server) => Boolean(server?.urls))
    .map((server) =>
      server.username && server.credential
        ? { urls: server.urls, username: server.username, credential: server.credential }
        : { urls: server.urls },
    );
}

export async function fetchIceServers(httpBase: string): Promise<RTCIceServer[]> {
  const response = await fetch(`${httpBase}/api/webrtc/ice-servers`);
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return toRtcIceServers((await response.json()) as RawIceServer[]);
}

export function generatePeerId(): string {
  return crypto.randomUUID().slice(0, 8);
}
