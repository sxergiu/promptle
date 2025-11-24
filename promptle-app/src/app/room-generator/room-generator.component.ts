import {afterNextRender, Component, Inject, OnInit, PLATFORM_ID, signal} from '@angular/core';
import {isPlatformBrowser} from '@angular/common';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';

@Component({
  selector: 'app-room-generator',
  imports: [
    RouterLink
  ],
  templateUrl: './room-generator.component.html',
  styleUrl: './room-generator.component.scss'
})
export class RoomGeneratorComponent implements OnInit{
  generatedLink = signal('');
  currentRoomId = signal('');
  copied = signal(false);
  activeRooms = signal<string[]>([]);
  fullRoomUrl = signal('');
  private isBrowser: boolean;

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    @Inject(PLATFORM_ID) platformId: Object
  ) {
    this.isBrowser = isPlatformBrowser(platformId);

    // Handle initial room ID from URL - works in both SSR and browser
    const roomParam = this.route.snapshot.queryParams['room'];
    if (roomParam) {
      this.currentRoomId.set(roomParam);
      console.log('Constructor - found room:', roomParam);
    }

    // After hydration completes, update the full URL
    if (this.isBrowser) {
      afterNextRender(() => {
        if (this.currentRoomId()) {
          const url = `${window.location.origin}${window.location.pathname}?room=${this.currentRoomId()}`;
          this.fullRoomUrl.set(url);
          console.log('After render - set fullRoomUrl:', url);
        }
      });
    }
  }

  ngOnInit(): void {
    console.log('ngOnInit called');
    console.log('Initial currentRoomId:', this.currentRoomId());
    console.log('Snapshot params:', this.route.snapshot.queryParams);

    // Subscribe to query param changes for in-app navigation
    this.route.queryParams.subscribe(params => {
      console.log('Query params subscription fired:', params);
      const currentRoomId = this.currentRoomId();
      const requestedRoom = params['room'];

      if (requestedRoom && requestedRoom !== currentRoomId) {
        this.currentRoomId.set(requestedRoom);
        if (this.isBrowser) {
          this.fullRoomUrl.set(`${window.location.origin}${window.location.pathname}?room=${requestedRoom}`);
        }
        console.log('Updated to room:', requestedRoom);
      } else if (!requestedRoom && currentRoomId) {
        this.currentRoomId.set('');
        this.fullRoomUrl.set('');
        console.log('Cleared room');
      }
    });
  }

  generateRoom(): void {
    if (!this.isBrowser) return;

    // Generate unique room ID
    const roomId = this.generateUniqueId();
    this.activeRooms.update(rooms => [...rooms, roomId]);

    // Create the full URL
    const baseUrl = window.location.origin + window.location.pathname;
    this.generatedLink.set(`${baseUrl}?room=${roomId}`);
    this.copied.set(false);
  }

  generateUniqueId(): string {
    // Generate a unique 8-character code
    return Math.random().toString(36).substring(2, 10).toUpperCase();
  }

  copyLink(): void {
    if (!this.isBrowser) return;

    navigator.clipboard.writeText(this.generatedLink());
    this.copied.set(true);
    setTimeout(() => {
      this.copied.set(false);
    }, 2000);
  }

  joinRoom(): void {
    const link = this.generatedLink();
    if (!link) {
      return;
    }

    // Extract room ID from generated link
    const roomId = link.split('room=')[1];
    if (roomId) {
      this.navigateToRoom(roomId);
    }
  }

  navigateToRoom(roomId: string): void {
    this.router.navigate([], {
      queryParams: { room: roomId },
      queryParamsHandling: 'merge'
    });
    this.currentRoomId.set(roomId);
    if (this.isBrowser) {
      this.fullRoomUrl.set(`${window.location.origin}${window.location.pathname}?room=${roomId}`);
    }
  }

  leaveRoom(): void {
    this.router.navigate([], {
      queryParams: {}
    });
    this.currentRoomId.set('');
    this.generatedLink.set('');
    this.fullRoomUrl.set('');
    this.copied.set(false);
  }

}
