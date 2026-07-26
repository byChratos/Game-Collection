import { useCallback, useEffect, useRef, useState } from "react";
import { Message } from "./Message";
import { MessageType } from "./MessageType";
import { P2PMessageHandler } from "./P2PMessageHandler";

/**
 * Thin control client for the local Kotlin sidecar.
 * WebRTC is implemented in Kotlin, this is a connectivity layer to communicate with Kotlin
 */

const CONTROL_SOCKET_URL = "ws://localhost:8721/ws/room";

export type RoomStatus = "idle" | "connecting" | "connected" | "error";

export type PeerInfo = {
  peerId: string;
  connectionState: string;
  channelOpen: boolean;
};

/** Mirrors de.cfe.gamecollection.backend.webrtc.RoomCommand */
type RoomCommand =
  | { type: "JOIN"; server: string; roomId: string }
  | { type: "LEAVE" }
  | { type: "SEND"; text: string };

/** Mirrors de.cfe.gamecollection.backend.webrtc.RoomChatMessage */
type RoomChatMessage = {
  id: string;
  senderId: string;
  text: string;
  fromSelf: boolean;
  at: number;
};

/** Mirrors de.cfe.gamecollection.backend.webrtc.RoomEvent */
type RoomEvent = {
  type: "STATUS" | "PEERS" | "MESSAGE";
  status?: "IDLE" | "CONNECTING" | "CONNECTED" | "ERROR";
  roomId?: string;
  localPeerId?: string;
  error?: string;
  warning?: string;
  peers?: PeerInfo[];
  message?: RoomChatMessage;
};

export function useWebRtcRoom() {
  const [status, setStatus] = useState<RoomStatus>("idle");
  const [error, setError] = useState("");
  const [warning, setWarning] = useState("");
  const [roomId, setRoomId] = useState("");
  const [localPeerId, setLocalPeerId] = useState("");
  const [peers, setPeers] = useState<PeerInfo[]>([]);
  const [messages, setMessages] = useState<Message[]>([]);

  const socketRef = useRef<WebSocket | null>(null);
  const leavingRef = useRef(false);

  const send = useCallback((command: RoomCommand) => {
    const socket = socketRef.current;
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(command));
    }
  }, []);

  /** Stable for the hook's lifetime — other controllers (e.g. a ChessController) can hold onto it. */
  const messageHandlerRef = useRef<P2PMessageHandler | null>(null);
  if (!messageHandlerRef.current) {
    messageHandlerRef.current = new P2PMessageHandler((raw) => send({ type: "SEND", text: raw }));
  }

  const teardown = useCallback(() => {
    const socket = socketRef.current;
    socketRef.current = null;
    if (socket) {
      socket.onclose = null;
      socket.onerror = null;
      socket.onmessage = null;
      socket.close();
    }
  }, []);

  const handleEvent = useCallback((event: RoomEvent) => {
    switch (event.type) {
      case "STATUS":
        if (event.status) setStatus(event.status.toLowerCase() as RoomStatus);
        if (event.roomId) setRoomId(event.roomId);
        if (event.localPeerId) setLocalPeerId(event.localPeerId);
        if (event.error) setError(event.error);
        if (event.warning) setWarning(event.warning);
        break;

      case "PEERS":
        setPeers(event.peers ?? []);
        break;

      case "MESSAGE":
        if (event.message) {
          const { id, senderId, text, fromSelf, at } = event.message;
          messageHandlerRef.current!.receive(text, senderId, id, fromSelf, at);
        }
        break;
    }
  }, []);

  // CHAT feeds the message list the room UI renders; other types (e.g. a ChessController's
  // MessageType.CHESS) are consumed by subscribing to messageHandler directly.
  useEffect(() => {
    return messageHandlerRef.current!.on(MessageType.CHAT, (message) => {
      setMessages((prev) => [...prev, message]);
    });
  }, []);

  const connect = useCallback(
    (address: string, identifier: string) => {
      teardown();
      leavingRef.current = false;
      setError("");
      setWarning("");
      setMessages([]);
      setPeers([]);
      setStatus("connecting");

      const socket = new WebSocket(CONTROL_SOCKET_URL);
      socketRef.current = socket;

      socket.onopen = () =>
        socket.send(JSON.stringify({ type: "JOIN", server: address, roomId: identifier }));

      socket.onmessage = (event) => {
        try {
          handleEvent(JSON.parse(String(event.data)) as RoomEvent);
        } catch {
          setError("Unreadable response from backend");
        }
      };

      socket.onclose = () => {
        if (leavingRef.current) return;
        teardown();
        setPeers([]);
        setError(`Local backend not reachable at ${CONTROL_SOCKET_URL}`);
        setStatus("error");
      };
    },
    [handleEvent, teardown],
  );

  const disconnect = useCallback(() => {
    leavingRef.current = true;
    send({ type: "LEAVE" });
    teardown();
    setPeers([]);
    setStatus("idle");
    setError("");
    setWarning("");
  }, [send, teardown]);

  const sendMessage = useCallback((text: string) => {
    const trimmed = text.trim();
    if (!trimmed) return;
    // The sidecar echoes the message back as fromSelf once it reached at least one peer.
    messageHandlerRef.current!.send(MessageType.CHAT, trimmed);
  }, []);

  useEffect(() => teardown, [teardown]);

  return {
    status,
    error,
    warning,
    roomId,
    localPeerId,
    peers,
    messages,
    connect,
    disconnect,
    sendMessage,
    /** For other game controllers (e.g. a ChessController) to send/receive their own message kinds. */
    messageHandler: messageHandlerRef.current,
  };
}
