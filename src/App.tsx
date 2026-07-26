import { useCallback, useEffect, useRef, useState } from "react";
import { ConnectForm } from "./components/ConnectForm";
import { RoomView } from "./components/RoomView";
import { useWebRtcRoom } from "./webrtc/useWebRtcRoom";
import "./App.css";

const BACKEND_URL = "http://localhost:8721";

type Game = { id: number; title: string };

function App() {
  const [games, setGames] = useState<Game[]>([]);
  const [backendError, setBackendError] = useState("");
  const [requestDurationMs, setRequestDurationMs] = useState<number | null>(null);
  const [isFetchingGames, setIsFetchingGames] = useState(false);
  const cancelledRef = useRef(false);
  const room = useWebRtcRoom();

  const loadGames = useCallback(async (retriesLeft: number) => {
    setIsFetchingGames(true);
    const start = performance.now();
    try {
      const response = await fetch(`${BACKEND_URL}/api/games`);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data: Game[] = await response.json();
      const duration = performance.now() - start;
      console.log(`[backend] GET /api/games antwortete nach ${duration.toFixed(1)} ms`);
      if (!cancelledRef.current) {
        setGames(data);
        setBackendError("");
        setRequestDurationMs(duration);
      }
    } catch (err) {
      // The Kotlin backend sidecar needs a moment to boot up, so retry a few times.
      if (retriesLeft > 0) {
        setTimeout(() => loadGames(retriesLeft - 1), 1000);
        return;
      }
      if (!cancelledRef.current) {
        setBackendError(
          `Backend nicht erreichbar unter ${BACKEND_URL}: ${(err as Error).message}`,
        );
      }
    }
    if (!cancelledRef.current) {
      setIsFetchingGames(false);
    }
  }, []);

  useEffect(() => {
    cancelledRef.current = false;
    loadGames(10);
    return () => {
      cancelledRef.current = true;
    };
  }, [loadGames]);

  if (room.status === "connected") {
    return (
      <main className="container">
        <RoomView
          roomId={room.roomId}
          localPeerId={room.localPeerId}
          peers={room.peers}
          messages={room.messages}
          error={room.error}
          warning={room.warning}
          onSend={room.sendMessage}
          onLeave={room.disconnect}
        />
      </main>
    );
  }

  return (
    <main className="container">
      <h1>Welcome to Tauri + React</h1>

      <h2>Mit anderen Spielern verbinden</h2>
      <ConnectForm status={room.status} error={room.error} onConnect={room.connect} />

      <h2>Games (from Kotlin Spring Boot backend)</h2>
      <button onClick={() => loadGames(0)} disabled={isFetchingGames}>
        {isFetchingGames ? "Lädt…" : "Request erneut senden"}
      </button>
      {requestDurationMs !== null && (
        <p style={{ fontSize: "0.85em", opacity: 0.7 }}>
          Request-Dauer: {requestDurationMs.toFixed(1)} ms
        </p>
      )}
      {backendError ? (
        <p style={{ color: "crimson" }}>{backendError}</p>
      ) : games.length === 0 ? (
        <p>Loading games from backend…</p>
      ) : (
        <ul style={{ textAlign: "left" }}>
          {games.map((game) => (
            <li key={game.id}>{game.title}</li>
          ))}
        </ul>
      )}
    </main>
  );
}

export default App;
