import { useCallback, useEffect, useRef, useState } from "react";
import reactLogo from "./assets/react.svg";
import { invoke } from "@tauri-apps/api/core";
import { checkForAppUpdates } from "./libraries/Update";
import "./App.css";

const BACKEND_URL = "http://127.0.0.1:8721";

type Game = { id: number; title: string };

function App() {
  const [greetMsg, setGreetMsg] = useState("");
  const [name, setName] = useState("");
  const [games, setGames] = useState<Game[]>([]);
  const [backendError, setBackendError] = useState("");
  const [requestDurationMs, setRequestDurationMs] = useState<number | null>(null);
  const [isFetchingGames, setIsFetchingGames] = useState(false);
  const cancelledRef = useRef(false);

  async function greet() {
    // Learn more about Tauri commands at https://tauri.app/develop/calling-rust/
    setGreetMsg(await invoke("greet", { name }));
  }

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

  useEffect(() => {
    checkForAppUpdates(false);
  }, []);

  return (
    <main className="container">
      <h1>Welcome to Tauri + React and in Version 0.3.0</h1>

      <div className="row">
        <a href="https://vite.dev" target="_blank">
          <img src="/vite.svg" className="logo vite" alt="Vite logo" />
        </a>
        <a href="https://tauri.app" target="_blank">
          <img src="/tauri.svg" className="logo tauri" alt="Tauri logo" />
        </a>
        <a href="https://react.dev" target="_blank">
          <img src={reactLogo} className="logo react" alt="React logo" />
        </a>
      </div>
      <p>Click on the Tauri, Vite, and React logos to learn more.</p>

      <form
        className="row"
        onSubmit={(e) => {
          e.preventDefault();
          greet();
        }}
      >
        <input
          id="greet-input"
          onChange={(e) => setName(e.currentTarget.value)}
          placeholder="Enter a name..."
        />
        <button type="submit">Greet</button>
      </form>
      <p>{greetMsg}</p>

      <h2>Games (from Kotlin Spring Boot backend)</h2>
      <button onClick={() => loadGames(0)} disabled={isFetchingGames}>
        {isFetchingGames ? "Lädt…" : "Request erneut senden"}
      </button>
      <button onClick={() => checkForAppUpdates(true)}>Check for Update</button>
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
