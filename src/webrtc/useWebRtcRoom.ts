import { useCallback, useEffect, useRef, useState } from "react";
import {
  fetchIceServers,
  generatePeerId,
  parseServerAddress,
  signalingUrl,
  type SignalMessage,
} from "./signaling";

export type RoomStatus = "idle" | "connecting" | "connected" | "error";

export type PeerInfo = {
  peerId: string;
  connectionState: RTCPeerConnectionState;
  channelOpen: boolean;
};

export type RoomMessage = {
  id: string;
  senderId: string;
  text: string;
  fromSelf: boolean;
  at: number;
};

type PeerEntry = {
  pc: RTCPeerConnection;
  channel: RTCDataChannel | null;
  /** Candidates that arrived before setRemoteDescription and have to be replayed afterwards. */
  pendingCandidates: RTCIceCandidateInit[];
  remoteDescriptionSet: boolean;
};

const DATA_CHANNEL_LABEL = "game-collection";

export function useWebRtcRoom() {
  const [status, setStatus] = useState<RoomStatus>("idle");
  const [error, setError] = useState("");
  const [warning, setWarning] = useState("");
  const [roomId, setRoomId] = useState("");
  const [localPeerId, setLocalPeerId] = useState("");
  const [peers, setPeers] = useState<PeerInfo[]>([]);
  const [messages, setMessages] = useState<RoomMessage[]>([]);

  const socketRef = useRef<WebSocket | null>(null);
  const peersRef = useRef(new Map<string, PeerEntry>());
  const iceServersRef = useRef<RTCIceServer[]>([]);
  const localPeerIdRef = useRef("");
  const roomIdRef = useRef("");
  const leavingRef = useRef(false);

  const syncPeers = useCallback(() => {
    setPeers(
      [...peersRef.current.entries()].map(([peerId, entry]) => ({
        peerId,
        connectionState: entry.pc.connectionState,
        channelOpen: entry.channel?.readyState === "open",
      })),
    );
  }, []);

  const sendSignal = useCallback((message: SignalMessage) => {
    const socket = socketRef.current;
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(message));
    }
  }, []);

  const appendMessage = useCallback((senderId: string, text: string, fromSelf: boolean) => {
    setMessages((prev) => [
      ...prev,
      { id: crypto.randomUUID(), senderId, text, fromSelf, at: Date.now() },
    ]);
  }, []);

  const closePeer = useCallback(
    (peerId: string) => {
      const entry = peersRef.current.get(peerId);
      if (!entry) return;
      entry.channel?.close();
      entry.pc.close();
      peersRef.current.delete(peerId);
      syncPeers();
    },
    [syncPeers],
  );

  const attachChannel = useCallback(
    (peerId: string, channel: RTCDataChannel) => {
      const entry = peersRef.current.get(peerId);
      if (!entry) return;
      entry.channel = channel;
      channel.onopen = syncPeers;
      channel.onclose = syncPeers;
      channel.onmessage = (event) => appendMessage(peerId, String(event.data), false);
      syncPeers();
    },
    [appendMessage, syncPeers],
  );

  const createPeer = useCallback(
    (peerId: string, isInitiator: boolean): PeerEntry => {
      const pc = new RTCPeerConnection({ iceServers: iceServersRef.current });
      const entry: PeerEntry = {
        pc,
        channel: null,
        pendingCandidates: [],
        remoteDescriptionSet: false,
      };
      peersRef.current.set(peerId, entry);

      pc.onicecandidate = (event) => {
        if (!event.candidate) return;
        sendSignal({
          type: "ICE_CANDIDATE",
          roomId: roomIdRef.current,
          senderId: localPeerIdRef.current,
          targetId: peerId,
          candidate: event.candidate.candidate,
          sdpMid: event.candidate.sdpMid,
          sdpMLineIndex: event.candidate.sdpMLineIndex,
        });
      };

      pc.onconnectionstatechange = () => {
        if (pc.connectionState === "failed" || pc.connectionState === "closed") {
          closePeer(peerId);
        } else {
          syncPeers();
        }
      };

      // Only the initiator opens the channel; the answering side receives it via ondatachannel.
      if (isInitiator) {
        attachChannel(peerId, pc.createDataChannel(DATA_CHANNEL_LABEL));
      } else {
        pc.ondatachannel = (event) => attachChannel(peerId, event.channel);
      }

      syncPeers();
      return entry;
    },
    [attachChannel, closePeer, sendSignal, syncPeers],
  );

  const flushCandidates = useCallback(async (entry: PeerEntry) => {
    entry.remoteDescriptionSet = true;
    const queued = entry.pendingCandidates.splice(0);
    for (const candidate of queued) {
      await entry.pc.addIceCandidate(candidate);
    }
  }, []);

  const handleSignal = useCallback(
    async (message: SignalMessage) => {
      switch (message.type) {
        case "JOIN":
          // We are the newcomer. Peers already in the room will send us an offer, so we just wait.
          break;

        case "PEER_JOINED": {
          if (peersRef.current.has(message.senderId)) break;
          const entry = createPeer(message.senderId, true);
          const offer = await entry.pc.createOffer();
          await entry.pc.setLocalDescription(offer);
          sendSignal({
            type: "OFFER",
            roomId: roomIdRef.current,
            senderId: localPeerIdRef.current,
            targetId: message.senderId,
            sdp: offer.sdp,
          });
          break;
        }

        case "OFFER": {
          if (!message.sdp) break;
          const entry =
            peersRef.current.get(message.senderId) ?? createPeer(message.senderId, false);
          await entry.pc.setRemoteDescription({ type: "offer", sdp: message.sdp });
          await flushCandidates(entry);
          const answer = await entry.pc.createAnswer();
          await entry.pc.setLocalDescription(answer);
          sendSignal({
            type: "ANSWER",
            roomId: roomIdRef.current,
            senderId: localPeerIdRef.current,
            targetId: message.senderId,
            sdp: answer.sdp,
          });
          break;
        }

        case "ANSWER": {
          const entry = peersRef.current.get(message.senderId);
          if (!entry || !message.sdp) break;
          await entry.pc.setRemoteDescription({ type: "answer", sdp: message.sdp });
          await flushCandidates(entry);
          break;
        }

        case "ICE_CANDIDATE": {
          const entry = peersRef.current.get(message.senderId);
          if (!entry || !message.candidate) break;
          const candidate: RTCIceCandidateInit = {
            candidate: message.candidate,
            sdpMid: message.sdpMid ?? undefined,
            sdpMLineIndex: message.sdpMLineIndex ?? undefined,
          };
          if (entry.remoteDescriptionSet) {
            await entry.pc.addIceCandidate(candidate);
          } else {
            entry.pendingCandidates.push(candidate);
          }
          break;
        }

        case "PEER_LEFT":
          closePeer(message.senderId);
          break;

        case "LEAVE":
          break;
      }
    },
    [closePeer, createPeer, flushCandidates, sendSignal],
  );

  const teardown = useCallback(() => {
    peersRef.current.forEach((entry) => {
      entry.channel?.close();
      entry.pc.close();
    });
    peersRef.current.clear();
    setPeers([]);

    const socket = socketRef.current;
    socketRef.current = null;
    if (socket) {
      socket.onclose = null;
      socket.onerror = null;
      socket.onmessage = null;
      socket.close();
    }
  }, []);

  const connect = useCallback(
    async (address: string, identifier: string) => {
      const trimmedRoom = identifier.trim();
      if (!trimmedRoom) {
        setError("Bitte einen Identifier für die Session angeben.");
        setStatus("error");
        return;
      }

      let bases: { httpBase: string; wsBase: string };
      try {
        bases = parseServerAddress(address);
      } catch (err) {
        setError((err as Error).message);
        setStatus("error");
        return;
      }

      teardown();
      leavingRef.current = false;
      setError("");
      setWarning("");
      setMessages([]);
      setStatus("connecting");

      try {
        iceServersRef.current = await fetchIceServers(bases.httpBase);
      } catch {
        // Without STUN/TURN only host candidates are gathered, which still covers a shared LAN.
        iceServersRef.current = [];
        setWarning(
          "ICE-Server konnten nicht geladen werden – Verbindungen funktionieren nur im lokalen Netz.",
        );
      }

      const peerId = generatePeerId();
      localPeerIdRef.current = peerId;
      roomIdRef.current = trimmedRoom;
      setLocalPeerId(peerId);
      setRoomId(trimmedRoom);

      const socket = new WebSocket(signalingUrl(bases.wsBase, trimmedRoom, peerId));
      socketRef.current = socket;

      socket.onopen = () => setStatus("connected");
      socket.onmessage = (event) => {
        void handleSignal(JSON.parse(String(event.data)) as SignalMessage).catch((err) =>
          setError(`Signaling-Fehler: ${(err as Error).message}`),
        );
      };
      socket.onclose = () => {
        if (leavingRef.current) return;
        teardown();
        setError(`Verbindung zum Signaling-Server ${bases.wsBase} wurde getrennt.`);
        setStatus("error");
      };
    },
    [handleSignal, teardown],
  );

  const disconnect = useCallback(() => {
    leavingRef.current = true;
    sendSignal({
      type: "LEAVE",
      roomId: roomIdRef.current,
      senderId: localPeerIdRef.current,
    });
    teardown();
    setStatus("idle");
    setError("");
    setWarning("");
  }, [sendSignal, teardown]);

  const sendMessage = useCallback(
    (text: string) => {
      const trimmed = text.trim();
      if (!trimmed) return;
      let delivered = 0;
      peersRef.current.forEach((entry) => {
        if (entry.channel?.readyState === "open") {
          entry.channel.send(trimmed);
          delivered += 1;
        }
      });
      if (delivered > 0) appendMessage(localPeerIdRef.current, trimmed, true);
    },
    [appendMessage],
  );

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
  };
}
