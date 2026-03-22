import { Routes } from '@angular/router';
import { RoomGeneratorComponent } from './room-generator/room-generator.component';
import { ImageGeneratorComponent } from './image-generator/image-generator.component';
import { HomeComponent } from './features/home/home.component';
import { JoinComponent } from './features/join/join.component';
import { LobbyComponent } from './features/lobby/lobby.component';
import { playerTokenGuard } from './core/guards/player-token.guard';

export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'join/:roomCode', component: JoinComponent },
  { path: 'lobby/:roomCode', component: LobbyComponent, canActivate: [playerTokenGuard] },
  { path: 'room-generator', component: RoomGeneratorComponent },
  { path: 'image-generator', component: ImageGeneratorComponent },
  { path: '**', redirectTo: '' }
];
