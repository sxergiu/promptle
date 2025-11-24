import { Routes } from '@angular/router';
import {RoomGeneratorComponent} from './room-generator/room-generator.component';
import {ImageGeneratorComponent} from './image-generator/image-generator.component';
import {PlayerPageComponent} from './player-page/player-page.component';

export const routes: Routes = [
  { path: '', component: PlayerPageComponent },
  { path: 'room-generator', component: RoomGeneratorComponent },
  { path: 'image-generator', component: ImageGeneratorComponent },
  { path: '**', redirectTo: '' }
];
