export interface PlayerDto {
  id: string;
  alias: string;
  avatarId: string;
  connected: boolean;
  returnedToLobby?: boolean;
}
