import { Injectable } from '@angular/core';
import { Client, StompSubscription } from '@stomp/stompjs';

@Injectable({ providedIn: 'root' })
export class WebSocketService {
  private client!: Client;

  connect(token: string, roomCode: string, onConnect?: () => void, reconnectDelay = 5000, onDisconnect?: () => void): void {
    if (this.client?.connected) {
      this.client.reconnectDelay = reconnectDelay;
      if (onDisconnect) {
        this.client.onDisconnect = () => onDisconnect();
      }
      onConnect?.();
      return;
    }
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const host = window.location.host;
    this.client = new Client({
      brokerURL: `${protocol}://${host}/ws?token=${token}&roomCode=${roomCode}`,
      reconnectDelay,
      onConnect: () => onConnect?.(),
      onDisconnect: () => onDisconnect?.(),
    });
    this.client.activate();
  }

  disconnect(): void {
    this.client?.deactivate();
  }

  isConnected(): boolean {
    return !!this.client?.connected;
  }

  subscribe(destination: string, callback: (body: unknown) => void): StompSubscription {
    return this.client.subscribe(destination, (message) => callback(JSON.parse(message.body)));
  }

  send(destination: string, body: unknown): void {
    this.client.publish({ destination, body: JSON.stringify(body) });
  }
}
