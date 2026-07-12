import "./App.css";

function App() {
  return (
    <main className="landing">
      <button
        type="button"
        className="settings-button"
        aria-label="Einstellungen"
        onClick={() => {}}
      >
        ⚙
      </button>

      <h1 className="landing-title">Game Collection</h1>

      <div className="landing-actions">
        <button type="button" className="landing-button" disabled>
          Spiel starten
        </button>
        <button type="button" className="landing-button" disabled>
          Bibliothek
        </button>
        <button type="button" className="landing-button" disabled>
          Statistiken
        </button>
      </div>
    </main>
  );
}

export default App;
