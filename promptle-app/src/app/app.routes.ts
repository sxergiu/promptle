import { Routes } from '@angular/router';
import {RoomGeneratorComponent} from './room-generator/room-generator.component';
import {ImageGeneratorComponent} from './image-generator/image-generator.component';
import {LandingPageComponent} from './landing-page/landing-page.component';

export const routes: Routes = [
  { path: '', component: LandingPageComponent },
  { path: 'room-generator', component: RoomGeneratorComponent },
  { path: 'image-generator', component: ImageGeneratorComponent },
  { path: '**', redirectTo: '' }
];
