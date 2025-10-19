import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, ActivatedRoute } from '@angular/router';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './app.html',
  styleUrls: ['./app.scss']
})
export class App {
  generatedLink: string = '';
  currentRoomId: string = '';
  copied: boolean = false;
  activeRooms: string[] = [];
  fullRoomUrl: string = '';

  constructor(
    private router: Router,
    private route: ActivatedRoute
  ) {
    // Check if we're already in a room from URL
    this.route.queryParams.subscribe(params => {
      if (params['room']) {
        this.currentRoomId = params['room'];
        this.fullRoomUrl = `${window.location.origin}${window.location.pathname}?room=${this.currentRoomId}`;
      }
    });
  }

  generateRoom(): void {
    // Generate unique room ID
    const roomId = this.generateUniqueId();
    this.activeRooms.push(roomId);

    // Create the full URL
    const baseUrl = window.location.origin + window.location.pathname;
    this.generatedLink = `${baseUrl}?room=${roomId}`;
    this.copied = false;
  }

  generateUniqueId(): string {
    // Generate a unique 8-character code
    return Math.random().toString(36).substring(2, 10).toUpperCase();
  }

  copyLink(): void {
    navigator.clipboard.writeText(this.generatedLink);
    this.copied = true;
    setTimeout(() => this.copied = false, 2000);
  }

  joinRoom(): void {
    // Extract room ID from generated link
    const roomId = this.generatedLink.split('room=')[1];
    this.navigateToRoom(roomId);
  }

  navigateToRoom(roomId: string): void {
    this.router.navigate([], {
      queryParams: { room: roomId },
      queryParamsHandling: 'merge'
    });
    this.currentRoomId = roomId;
    this.fullRoomUrl = `${window.location.origin}${window.location.pathname}?room=${roomId}`;
  }

  leaveRoom(): void {
    this.router.navigate([], {
      queryParams: {}
    });
    this.currentRoomId = '';
    this.generatedLink = '';
  }
}
