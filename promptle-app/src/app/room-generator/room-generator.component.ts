import { afterNextRender, Component, inject, Inject, OnInit, PLATFORM_ID, signal } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PlayerService } from '../service/player.service';
import { PlayerPageComponent } from '../player-page/player-page.component';
import { PlayerCardComponent } from '../player-card/player-card.component';

@Component({
  selector: 'app-room-generator',
  imports: [RouterLink, PlayerPageComponent, PlayerCardComponent],
  templateUrl: './room-generator.component.html',
  styleUrl: './room-generator.component.scss',
})
export class RoomGeneratorComponent implements OnInit {
  readonly player = inject(PlayerService);

  generatedLink = signal('');
  currentRoomId = signal('');
  copied = signal(false);
  activeRooms = signal<string[]>([]);
  fullRoomUrl = signal('');
  private isBrowser: boolean;

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    @Inject(PLATFORM_ID) platformId: Object,
  ) {
    this.isBrowser = isPlatformBrowser(platformId);

    const roomParam = this.route.snapshot.queryParams['room'];
    if (roomParam) {
      this.currentRoomId.set(roomParam);
    }

    if (this.isBrowser) {
      afterNextRender(() => {
        if (this.currentRoomId()) {
          const url = `${window.location.origin}${window.location.pathname}?room=${this.currentRoomId()}`;
          this.fullRoomUrl.set(url);
        }
      });
    }
  }

  ngOnInit(): void {
    this.route.queryParams.subscribe(params => {
      const currentRoomId = this.currentRoomId();
      const requestedRoom = params['room'];

      if (requestedRoom && requestedRoom !== currentRoomId) {
        this.currentRoomId.set(requestedRoom);
        if (this.isBrowser) {
          this.fullRoomUrl.set(
            `${window.location.origin}${window.location.pathname}?room=${requestedRoom}`,
          );
        }
      } else if (!requestedRoom && currentRoomId) {
        this.currentRoomId.set('');
        this.fullRoomUrl.set('');
      }
    });
  }

  generateRoom(): void {
    if (!this.isBrowser) return;
    const roomId = this.generateUniqueId();
    this.activeRooms.update(rooms => [...rooms, roomId]);
    const baseUrl = window.location.origin + window.location.pathname;
    this.generatedLink.set(`${baseUrl}?room=${roomId}`);
    this.copied.set(false);
  }

  generateUniqueId(): string {
    return Math.random().toString(36).substring(2, 10).toUpperCase();
  }

  copyLink(): void {
    if (!this.isBrowser) return;
    navigator.clipboard.writeText(this.generatedLink());
    this.copied.set(true);
    setTimeout(() => this.copied.set(false), 2000);
  }

  joinRoom(): void {
    const link = this.generatedLink();
    if (!link) return;
    const roomId = link.split('room=')[1];
    if (roomId) this.navigateToRoom(roomId);
  }

  navigateToRoom(roomId: string): void {
    this.router.navigate([], { queryParams: { room: roomId }, queryParamsHandling: 'merge' });
    this.currentRoomId.set(roomId);
    if (this.isBrowser) {
      this.fullRoomUrl.set(
        `${window.location.origin}${window.location.pathname}?room=${roomId}`,
      );
    }
  }

  leaveRoom(): void {
    this.router.navigate([], { queryParams: {} });
    this.currentRoomId.set('');
    this.generatedLink.set('');
    this.fullRoomUrl.set('');
    this.copied.set(false);
  }
}
