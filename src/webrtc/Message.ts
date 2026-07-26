import { MessageType } from "./MessageType";

/**
 * A message exchanged over the P2P data channel (only `type`/`content` cross the wire) or
 * rendered as a room chat/game event. `id`/`senderId`/`fromSelf`/`at` are attached locally by
 * whoever sent or received it. Mirrors de.cfe.gamecollection.backend.model.Message.
 */
export class Message {
  constructor(
    public readonly type: MessageType,
    public readonly content: string,
    public readonly id: string,
    public readonly senderId: string,
    public readonly fromSelf: boolean,
    public readonly at: number,
  ) {}

  static encode(type: MessageType, content: string): string {
    return JSON.stringify({ type, content });
  }

  /**
   * id/senderId/fromSelf/at come from the sidecar's RoomChatMessage; raw is its `text` field.
   * Anything that isn't one of our envelopes (e.g. a plain string) is treated as chat text, so
   * older peers or hand-typed messages still show up instead of being dropped.
   */
  static decode(
    raw: string,
    senderId: string,
    id: string,
    fromSelf: boolean,
    at: number,
  ): Message {
    try {
      const parsed = JSON.parse(raw) as { type?: unknown; content?: unknown };
      if (typeof parsed.type === "string" && typeof parsed.content === "string") {
        return new Message(parsed.type as MessageType, parsed.content, id, senderId, fromSelf, at);
      }
    } catch {
      // not one of our envelopes — fall through to plain chat text
    }
    return new Message(MessageType.CHAT, raw, id, senderId, fromSelf, at);
  }
}
