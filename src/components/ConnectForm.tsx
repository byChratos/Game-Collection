import { useEffect, useState } from "react";
import type { RoomStatus } from "../webrtc/useWebRtcRoom";

const STORAGE_KEY = "game-collection.signaling";

type Props = {
  status: RoomStatus;
  error: string;
  onConnect: (address: string, identifier: string) => void;
};

function addPort(address: string, port: string): string {
  return `${address}:${port}`;
}

export function ConnectForm({ status, error, onConnect }: Props) {
  const [address, setAddress] = useState("localhost:8721");
  const [roomId, setRoomId] = useState("");

  useEffect(() => {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (!stored) return;
    try {
      const parsed = JSON.parse(stored) as { address?: string; identifier?: string };
      if (parsed.address) setAddress(parsed.address);
      if (parsed.identifier) setRoomId(parsed.identifier);
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
        localStorage.setItem(STORAGE_KEY, JSON.stringify({ address, roomId }));
        onConnect(addPort(address, "8721"), roomId);
      }}
    >
      <label>
        Signaling-Server
        <input
          value={address}
          onChange={(event) => setAddress(event.currentTarget.value)}
          placeholder="z. B. cooler-test.duckdns.org"
          autoComplete="off"
          spellCheck={false}
        />
      </label>

      <label>
        Room ID
        <input
          value={roomId}
          onChange={(event) => setRoomId(event.currentTarget.value)}
          placeholder="z. B. abendrunde"
          autoComplete="off"
          spellCheck={false}
        />
      </label>

      <button type="submit" disabled={isConnecting}>
        {isConnecting ? "Verbinde…" : "Session beitreten"}
      </button>

      {error && <p className="error">{error}</p>}
    </form>
  );
}
