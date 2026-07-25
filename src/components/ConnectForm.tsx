import { useEffect, useState } from "react";
import type { RoomStatus } from "../webrtc/useWebRtcRoom";

const STORAGE_KEY = "game-collection.signaling";

type Props = {
  status: RoomStatus;
  error: string;
  onConnect: (address: string, identifier: string) => void;
};

export function ConnectForm({ status, error, onConnect }: Props) {
  const [address, setAddress] = useState("localhost:8721");
  const [identifier, setIdentifier] = useState("");

  useEffect(() => {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (!stored) return;
    try {
      const parsed = JSON.parse(stored) as { address?: string; identifier?: string };
      if (parsed.address) setAddress(parsed.address);
      if (parsed.identifier) setIdentifier(parsed.identifier);
    } catch {
      localStorage.removeItem(STORAGE_KEY);
    }
  }, []);

  const isConnecting = status === "connecting";

  return (
    <form
      className="connect-form"
      onSubmit={(event) => {
        event.preventDefault();
        localStorage.setItem(STORAGE_KEY, JSON.stringify({ address, identifier }));
        onConnect(address, identifier);
      }}
    >
      <label>
        Signaling-Server
        <input
          value={address}
          onChange={(event) => setAddress(event.currentTarget.value)}
          placeholder="z. B. 192.168.1.5:8721"
          autoComplete="off"
          spellCheck={false}
        />
      </label>

      <label>
        Identifier
        <input
          value={identifier}
          onChange={(event) => setIdentifier(event.currentTarget.value)}
          placeholder="z. B. abendrunde"
          autoComplete="off"
          spellCheck={false}
        />
      </label>

      <button type="submit" disabled={isConnecting}>
        {isConnecting ? "Verbinde…" : "Session beitreten"}
      </button>

      <p className="hint">
        Alle, die denselben Identifier auf demselben Server eingeben, werden per WebRTC direkt
        miteinander verbunden.
      </p>

      {error && <p className="error">{error}</p>}
    </form>
  );
}
