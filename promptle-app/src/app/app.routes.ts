import { Routes } from '@angular/router';
import { HomeComponent } from './features/home/home.component';
import { JoinComponent } from './features/join/join.component';
import { LobbyComponent } from './features/lobby/lobby.component';
import { GameComponent } from './features/game/game.component';
import { ResultsComponent } from './features/results/results.component';
import { playerTokenGuard } from './core/guards/player-token.guard';

export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'join/:roomCode', component: JoinComponent },
  { path: 'lobby/:roomCode', component: LobbyComponent, canActivate: [playerTokenGuard] },
  {
    path: 'game/:roomCode',
    component: GameComponent,
    canActivate: [playerTokenGuard],
    children: [
      { path: 'results', component: ResultsComponent, canActivate: [playerTokenGuard] },
    ],
  },
  { path: '**', redirectTo: '' }
];
