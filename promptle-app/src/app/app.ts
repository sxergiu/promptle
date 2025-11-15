import { Component, Inject, PLATFORM_ID, OnInit, ChangeDetectorRef, afterNextRender } from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import {Router, ActivatedRoute, RouterLink, RouterOutlet} from '@angular/router';
import {ImageGeneratorComponent} from './image-generator/image-generator.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, ImageGeneratorComponent, RouterLink, RouterOutlet],
  templateUrl: './app.html',
  styleUrls: ['./app.scss']
})
export class App {

}
