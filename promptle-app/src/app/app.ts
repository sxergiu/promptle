import { Component, Inject, PLATFORM_ID, OnInit, ChangeDetectorRef, afterNextRender } from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { Router, ActivatedRoute } from '@angular/router';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './app.html',
  styleUrls: ['./app.scss']
})
export class App implements OnInit {
  generatedLink: string = '';
  currentRoomId: string = '';
  copied: boolean = false;
  activeRooms: string[] = [];
  fullRoomUrl: string = '';
  private isBrowser: boolean;

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    private cdr: ChangeDetectorRef,
    @Inject(PLATFORM_ID) platformId: Object
  ) {
    this.isBrowser = isPlatformBrowser(platformId);

    // Handle initial room ID from URL - works in both SSR and browser
    const roomParam = this.route.snapshot.queryParams['room'];
    if (roomParam) {
      this.currentRoomId = roomParam;
      console.log('Constructor - found room:', roomParam);
    }

    // After hydration completes, update the full URL
    if (this.isBrowser) {
      afterNextRender(() => {
        if (this.currentRoomId) {
          this.fullRoomUrl = `${window.location.origin}${window.location.pathname}?room=${this.currentRoomId}`;
          this.cdr.markForCheck();
          console.log('After render - set fullRoomUrl:', this.fullRoomUrl);
        }
      });
    }
  }

  ngOnInit(): void {
    console.log('ngOnInit called');
    console.log('Initial currentRoomId:', this.currentRoomId);
    console.log('Snapshot params:', this.route.snapshot.queryParams);

    // Subscribe to query param changes for in-app navigation
    this.route.queryParams.subscribe(params => {
      console.log('Query params subscription fired:', params);

      if (params['room'] && params['room'] !== this.currentRoomId) {
        this.currentRoomId = params['room'];
        if (this.isBrowser) {
          this.fullRoomUrl = `${window.location.origin}${window.location.pathname}?room=${this.currentRoomId}`;
        }
        this.cdr.markForCheck();
        console.log('Updated to room:', this.currentRoomId);
      } else if (!params['room'] && this.currentRoomId) {
        this.currentRoomId = '';
        this.fullRoomUrl = '';
        this.cdr.markForCheck();
        console.log('Cleared room');
      }
    });
  }

  generateRoom(): void {
    if (!this.isBrowser) return;

    // Generate unique room ID
    const roomId = this.generateUniqueId();
    this.activeRooms.push(roomId);

    // Create the full URL
    const baseUrl = window.location.origin + window.location.pathname;
    this.generatedLink = `${baseUrl}?room=${roomId}`;
    this.copied = false;
    this.cdr.markForCheck();
  }

  generateUniqueId(): string {
    // Generate a unique 8-character code
    return Math.random().toString(36).substring(2, 10).toUpperCase();
  }

  copyLink(): void {
    if (!this.isBrowser) return;

    navigator.clipboard.writeText(this.generatedLink);
    this.copied = true;
    this.cdr.markForCheck();
    setTimeout(() => {
      this.copied = false;
      this.cdr.markForCheck();
    }, 2000);
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
    if (this.isBrowser) {
      this.fullRoomUrl = `${window.location.origin}${window.location.pathname}?room=${roomId}`;
    }
    this.cdr.markForCheck();
  }

  leaveRoom(): void {
    this.router.navigate([], {
      queryParams: {}
    });
    this.currentRoomId = '';
    this.generatedLink = '';
    this.cdr.markForCheck();
  }
}
