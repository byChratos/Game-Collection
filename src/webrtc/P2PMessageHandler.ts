import { Message } from "./Message";
import type { MessageType } from "./MessageType";

type Listener = (message: Message) => void;

/**
 * Sends and receives Messages over the room's data channel.
 *
 * Owns no transport itself — it is handed a `sendRaw` callback (the control WebSocket to the
 * sidecar) and fed every incoming envelope via `receive`. Game controllers (e.g. a
 * ChessController) subscribe with `on(type, ...)` instead of talking to the WebSocket directly,
 * so they don't need to know that a plain WebSocket to a local sidecar sits behind the P2P
 * connection at all.
 */
export class P2PMessageHandler {
  private readonly listeners = new Map<MessageType, Set<Listener>>();

  constructor(private readonly sendRaw: (raw: string) => void) {}

  send(type: MessageType, content: string): void {
    this.sendRaw(Message.encode(type, content));
  }

  /** Called by useWebRtcRoom for every message the sidecar delivers; dispatches by type. */
  receive(raw: string, senderId: string, id: string, fromSelf: boolean, at: number): Message {
    const message = Message.decode(raw, senderId, id, fromSelf, at);
    this.listeners.get(message.type)?.forEach((listener) => listener(message));
    return message;
  }

  /** Subscribe to messages of a given type, e.g. handler.on(MessageType.CHESS, handleMove). */
  on(type: MessageType, listener: Listener): () => void {
    const set = this.listeners.get(type) ?? new Set();
    set.add(listener);
    this.listeners.set(type, set);
    return () => set.delete(listener);
  }
}
