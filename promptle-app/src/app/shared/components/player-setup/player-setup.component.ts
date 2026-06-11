import { Component, EventEmitter, Input, OnInit, Output, signal } from '@angular/core';

import { PlayerIcon, PLAYER_ICONS } from '../../../core/models/player-icons';

export interface PlayerSetupSubmit {
  alias: string;
  avatarId: string;
}

export interface InfoSection {
  key: string;
  label: string;
  title: string;
  body: string[];
}

const INFO_SECTIONS: InfoSection[] = [
  {
    key: 'how-to-play',
    label: 'How to Play',
    title: 'How to Play',
    body: [
      'Invite some friends, lock in your ideas, generate images, guess prompts — and on it goes.',
      'Watch the creations of Promptle unfold before you.',
    ],
  },
  {
    key: 'terms',
    label: 'Terms of Service',
    title: 'Terms of Service',
    body: [
      'A party game, provided as-is and just for fun. This is a placeholder text. So it all fits nicely.',
      'Play with people you trust. Not many options.',
    ],
  },
  {
    key: 'safety',
    label: 'Usage Safety',
    title: 'Usage Safety',
    body: [
      'Prompts are filtered to block explicit content. We work to keep generated images appropriate.',
      'Images which might be found inappropriate are on you.',
    ],
  },
];

/**
 * Shared "set up your player" screen used by both Home (create) and Join.
 * Owns the brand, avatar picker and name field; the parent supplies the
 * action by handling (play) and may pass an errorMessage / busy state.
 */
@Component({
  selector: 'app-player-setup',
  standalone: true,
  imports: [],
  templateUrl: './player-setup.component.html',
  styleUrl: './player-setup.component.scss',
})
export class PlayerSetupComponent implements OnInit {
  @Input() errorMessage: string | null = null;
  @Input() busy = false;
  @Output() readonly play = new EventEmitter<PlayerSetupSubmit>();

  readonly alias = signal('');
  readonly aliasRejected = signal(false);
  diceSpinning = false;

  readonly infoSections = INFO_SECTIONS;
  readonly activeSection = signal<InfoSection | null>(null);

  toggleSection(section: InfoSection): void {
    this.activeSection.set(this.activeSection() === section ? null : section);
  }

  closeSection(): void {
    this.activeSection.set(null);
  }

  private readonly _selectedIcon = signal<PlayerIcon>(PLAYER_ICONS[0]);

  get selectedIcon(): PlayerIcon {
    return this._selectedIcon();
  }

  ngOnInit(): void {
    this._selectedIcon.set(PLAYER_ICONS[Math.floor(Math.random() * PLAYER_ICONS.length)]);
  }

  shuffleIcon(): void {
    this.diceSpinning = false;
    if (PLAYER_ICONS.length <= 1) return;
    const current = this._selectedIcon();
    let next = current;
    while (next === current) {
      next = PLAYER_ICONS[Math.floor(Math.random() * PLAYER_ICONS.length)];
    }
    this._selectedIcon.set(next);
  }

  onAliasInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.value.length > 13) {
      input.value = input.value.slice(0, 13);
      this.aliasRejected.set(true);
      setTimeout(() => this.aliasRejected.set(false), 400);
    } else {
      this.aliasRejected.set(false);
    }
    this.alias.set(input.value);
  }

  onPlay(): void {
    const alias = this.alias().trim();
    if (!alias || this.busy) return;
    this.play.emit({ alias, avatarId: this._selectedIcon().id });
  }
}
