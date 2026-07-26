/** Mirrors de.cfe.gamecollection.backend.model.MessageType. */
export const MessageType = {
  CHAT: "CHAT",
  CHESS: "CHESS",
  CONNECTION: "CONNECTION",
} as const;

export type MessageType = (typeof MessageType)[keyof typeof MessageType];
