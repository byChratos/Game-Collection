import { useState } from "react";
import type { Message } from "../webrtc/Message";
import type { PeerInfo } from "../webrtc/useWebRtcRoom";

type Props = {
  roomId: string;
  localPeerId: string;
  peers: PeerInfo[];
  messages: Message[];
  error: string;
  warning: string;
  onSend: (text: string) => void;
  onLeave: () => void;
};

export function RoomView({
  roomId,
  localPeerId,
  peers,
  messages,
  error,
  warning,
  onSend,
  onLeave,
}: Props) {
  const [draft, setDraft] = useState("");
  const connectedPeers = peers.filter((peer) => peer.channelOpen);

  return (
    <section className="room">
      <header className="room-header">
        <div>
          <h2>Session: {roomId}</h2>
          <p className="hint">
            Deine Peer-ID: <code>{localPeerId}</code> · {connectedPeers.length} von {peers.length}{" "}
            Peer(s) verbunden
          </p>
        </div>
        <button type="button" onClick={onLeave}>
          Verlassen
        </button>
      </header>

      {warning && <p className="warning">{warning}</p>}
      {error && <p className="error">{error}</p>}

      <h3>Peers</h3>
      {peers.length === 0 ? (
        <p className="hint">
          Noch niemand da. Sobald jemand denselben Identifier eingibt, verbindet ihr euch
          automatisch.
        </p>
      ) : (
        <ul className="peer-list">
          {peers.map((peer) => (
            <li key={peer.peerId}>
              <code>{peer.peerId}</code>
              <span className={peer.channelOpen ? "badge badge-ok" : "badge"}>
                {peer.channelOpen ? "P2P offen" : peer.connectionState}
              </span>
            </li>
          ))}
        </ul>
      )}

      <h3>Direktnachrichten (über den P2P-DataChannel)</h3>
      <ul className="message-list">
        {messages.map((message) => (
          <li key={message.id} className={message.fromSelf ? "message message-self" : "message"}>
            <code>{message.fromSelf ? "du" : message.senderId}</code> {message.content}
          </li>
        ))}
      </ul>

      <form
        className="row"
        onSubmit={(event) => {
          event.preventDefault();
          onSend(draft);
          setDraft("");
        }}
      >
        <input
          value={draft}
          onChange={(event) => setDraft(event.currentTarget.value)}
          placeholder="Nachricht an alle Peers…"
        />
        <button type="submit" disabled={connectedPeers.length === 0}>
          Senden
        </button>
      </form>
    </section>
  );
}
