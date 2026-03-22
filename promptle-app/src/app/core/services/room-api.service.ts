import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { GameStateSnapshot, JoinRoomResponse } from '../models/events.model';

@Injectable({ providedIn: 'root' })
export class RoomApiService {
  constructor(private http: HttpClient) {}

  createRoom(alias: string, avatarId: string): Observable<JoinRoomResponse> {
    return this.http.post<JoinRoomResponse>('/api/rooms', { alias, avatarId });
  }

  joinRoom(roomCode: string, alias: string, avatarId: string): Observable<JoinRoomResponse> {
    return this.http.post<JoinRoomResponse>(`/api/rooms/${roomCode}/join`, { alias, avatarId });
  }

  getGameStateSnapshot(roomCode: string, token: string): Observable<GameStateSnapshot> {
    return this.http.get<GameStateSnapshot>(`/api/rooms/${roomCode}/state`, { params: { token } });
  }

  startGame(roomCode: string, token: string): Observable<void> {
    return this.http.post<void>(`/api/rooms/${roomCode}/start`, null, { params: { token } });
  }
}
